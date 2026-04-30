package de.lhns.mcdp.neoforge;

import de.lhns.mcdp.api.McdpProvider;
import de.lhns.mcdp.core.LoaderCoordinator;
import de.lhns.mcdp.core.MixinConfigScanner;
import de.lhns.mcdp.core.ModClassLoader;
import de.lhns.mcdp.core.StdlibPromotion;
import de.lhns.mcdp.deps.LibraryCache;
import de.lhns.mcdp.deps.Manifest;
import de.lhns.mcdp.deps.ManifestConsumer;
import de.lhns.mcdp.deps.ManifestIo;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageLoader;
import net.neoforged.neoforgespi.language.ModFileScanData;

import java.lang.annotation.ElementType;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * NeoForge language loader. Registered via
 * {@code META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader}.
 * <p>
 * FML 4.0.x discovers language loaders on the PLUGIN module layer — the jar carrying this
 * class must declare {@code FMLModType: LIBRARY} in its manifest and must <em>not</em>
 * ship a {@code neoforge.mods.toml} (that would classify it as a MOD instead of a LIBRARY).
 * Mods opt in via their own mods.toml: {@code modLoader = "mcdepprovider"}.
 */
public final class McdpLanguageLoader implements IModLanguageLoader {

    public static final String LANGUAGE_ID = "mcdepprovider";
    private static final String LANGUAGE_VERSION = "1";
    private static final String MANIFEST_PATH = "META-INF/mcdepprovider.toml";

    private static final Logger LOG = LogUtils.getLogger();

    private static final LoaderCoordinator COORDINATOR =
            new LoaderCoordinator(McdpLanguageLoader.class.getClassLoader());

    private static final LibraryCache CACHE = LibraryCache.defaultCache();
    private static final ManifestConsumer CONSUMER = new ManifestConsumer(CACHE);

    private static final StdlibPromotion PROMOTION_POLICY = StdlibPromotion.defaults();
    private static final Object PROMOTION_LOCK = new Object();
    private static volatile Map<String, Manifest.Library> promotionSelection;
    private static volatile URLClassLoader promotedLoader;

    @Override
    public String name() {
        return LANGUAGE_ID;
    }

    @Override
    public String version() {
        return LANGUAGE_VERSION;
    }

    @Override
    public ModContainer loadMod(IModInfo info, ModFileScanData scanResults, ModuleLayer gameLayer) {
        String modId = info.getModId();
        // Idempotent path: classloader + bridges may already be registered if the eager static
        // block at the bottom of this class walked LoadingModList successfully (the only way
        // mods whose mixins fire during MC's Bootstrap.bootStrap can register before
        // Blocks.<clinit>). loadMod is still called by FML; we just skip the redundant work.
        Registered reg = ensureRegistered(info);

        // Mirror FMLModContainer's lifecycle: return an un-constructed container; FML drives
        // McdpModContainer.constructMod() at the CONSTRUCT stage, where the entry ctor gets
        // `this` (the container) plus IEventBus + Dist in the bag. See ADR-0017.
        Class<?> entryClass = loadEntryClass(scanResults, reg.loader(), modId);
        return new McdpModContainer(info, entryClass, reg.manifest().lang(), scanResults);
    }

    /** (loader, manifest) cached per modId once {@link #ensureRegistered} has run. */
    private record Registered(ModClassLoader loader, Manifest manifest) {}

    private static final java.util.concurrent.ConcurrentHashMap<String, Registered> REGISTERED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Build the per-mod {@link ModClassLoader}, register the mod with {@link McdpProvider}, and
     * register its auto-bridge manifest. Idempotent — second call for the same modId returns
     * the cached {@link Registered} record without rerunning manifest reads or library downloads.
     *
     * <p>This method is called from two places:
     * <ol>
     *   <li>The eager static block at the bottom of this class, which walks {@link
     *       LoadingModList} during early FML init. Mods whose mixins fire during MC's
     *       {@code Bootstrap.bootStrap} (i.e. before FML reaches the {@link #loadMod} dispatch
     *       phase) need their bridges registered before {@code Blocks.<clinit>} runs.
     *   <li>{@link #loadMod} itself, which FML calls per-mod after MC bootstrap. For mods whose
     *       eager-walk path completed, this is a cache hit; for mods that arrived too late for
     *       the eager walk (e.g. PLUGIN-layer libraries discovered post-init), this is the
     *       canonical registration.
     * </ol>
     */
    private static Registered ensureRegistered(IModInfo info) {
        String modId = info.getModId();
        Registered cached = REGISTERED.get(modId);
        if (cached != null) return cached;

        Path modFile = info.getOwningFile().getFile().getFilePath();
        Path manifestResource = info.getOwningFile().getFile().findResource(MANIFEST_PATH);
        Manifest manifest = readManifest(modFile, manifestResource, modId);
        List<Path> libs = downloadLibs(manifest, modId);

        ensurePromotionInitialized();
        Map<String, Manifest.Library> selected = promotionSelection;
        Manifest reducedManifest = new Manifest(
                manifest.lang(),
                manifest.sharedPackages(),
                PROMOTION_POLICY.stripPromoted(manifest, selected));
        List<Path> reducedLibs = filterNonPromoted(manifest, libs, selected);
        ClassLoader libParent = promotedLoader != null
                ? promotedLoader
                : McdpLanguageLoader.class.getClassLoader();

        // In dev mode, getFilePath() returns ONE path (typically build/resources/main, where
        // neoforge.mods.toml lives), but the mod's compiled classes live in sibling dirs
        // (build/classes/java/main, build/classes/scala/main, build/classes/kotlin/main, plus
        // the codegen's bridge-classes output). Without those on ModClassLoader's URL list,
        // mod-private classes load via FML's GAME-layer parent — bypassing libsLoader and its
        // Scala/Kotlin/etc deps. Fabric's McdpPreLaunch already does this expansion; mirror it
        // here. Production jars are unaffected (jar files don't have sibling-dir siblings).
        List<Path> modPaths = expandDevRoots(modFile);
        ModClassLoader loader = COORDINATOR.register(
                modId, reducedManifest, modPaths, reducedLibs, libParent);
        McdpProvider.registerMod(modId, loader);

        Path manifestToml = info.getOwningFile().getFile()
                .findResource("META-INF/mcdp-mixin-bridges.toml");
        if (manifestToml == null) {
            LOG.info("mcdepprovider: no auto-bridge manifest file for {} "
                    + "(mod has no mixins, or findResource returned null)", modId);
        } else {
            LOG.info("mcdepprovider: scanning {} for {} (exists={}, regular={})",
                    manifestToml, modId,
                    Files.exists(manifestToml), Files.isRegularFile(manifestToml));
            int registered = McdpProvider.registerAutoBridgeManifestToml(loader, manifestToml);
            LOG.info("mcdepprovider: registered {} auto-bridge entries for {}", registered, modId);
        }
        registerMixinOwnersForNeoForgeMod(info, modId);

        Registered reg = new Registered(loader, manifest);
        Registered raced = REGISTERED.putIfAbsent(modId, reg);
        return raced != null ? raced : reg;
    }

    /**
     * NeoForge calls {@link #loadMod} once per mod; StdlibPromotion needs the union of every
     * mod's manifest before it can pick a winner. First-call scans the full {@link LoadingModList},
     * reads each mcdepprovider-loaded mod's manifest, runs {@link StdlibPromotion#selectPromotions},
     * builds one shared {@link URLClassLoader} per promoted library, and caches both.
     */
    private static void ensurePromotionInitialized() {
        if (promotionSelection != null) return;
        synchronized (PROMOTION_LOCK) {
            if (promotionSelection != null) return;
            List<Manifest> allManifests = new ArrayList<>();
            try {
                for (IModInfo info : LoadingModList.get().getMods()) {
                    if (!LANGUAGE_ID.equals(info.getLoader().name())) continue;
                    Path modFile = info.getOwningFile().getFile().getFilePath();
                    Path resource = info.getOwningFile().getFile().findResource(MANIFEST_PATH);
                    try {
                        allManifests.add(readManifest(modFile, resource, info.getModId()));
                    } catch (IllegalStateException ignored) {
                        // skip mods we can't parse; loadMod will fail loud when they come through
                    }
                }
            } catch (Throwable t) {
                // If LoadingModList isn't accessible yet or throws, fall back to no-promotion.
                promotionSelection = Collections.emptyMap();
                return;
            }

            Map<String, Manifest.Library> selected = PROMOTION_POLICY.selectPromotions(allManifests);
            if (!selected.isEmpty()) {
                List<Path> jars = new ArrayList<>(selected.size());
                List<String> shas = new ArrayList<>(selected.size());
                for (Manifest.Library lib : selected.values()) {
                    try {
                        jars.add(CONSUMER.resolve(lib));
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "mcdepprovider: failed to resolve promoted lib " + lib.coords(), e);
                    }
                    shas.add(lib.sha256());
                }
                promotedLoader = COORDINATOR.buildSharedLibraryLoader(jars, shas);
            }
            promotionSelection = selected;
        }
    }

    /**
     * Pre-register mixin class FQNs → owning modId from this mod's {@code neoforge.mods.toml}
     * {@code [[mixins]]} entries (ADR-0008 path 2). Uses FML's own {@link IConfigurable} view so we
     * don't re-parse the TOML. Silent on any failure — the annotation {@code modId} path (path 1)
     * stays primary.
     */
    private static void registerMixinOwnersForNeoForgeMod(IModInfo info, String modId) {
        try {
            IConfigurable fileConfig = info.getOwningFile().getConfig();
            if (fileConfig == null) return;
            List<String> configPaths = new ArrayList<>();
            for (IConfigurable entry : fileConfig.getConfigList("mixins")) {
                entry.<String>getConfigElement("config").ifPresent(configPaths::add);
            }
            if (configPaths.isEmpty()) return;

            List<Path> resolved = new ArrayList<>(configPaths.size());
            List<String> relative = new ArrayList<>(configPaths.size());
            for (String cfg : configPaths) {
                Path p = info.getOwningFile().getFile().findResource(cfg);
                if (p != null) {
                    // Normalize to a root+relative view MixinConfigScanner expects.
                    resolved.add(p.getParent() != null ? p.getParent() : p);
                    relative.add(p.getFileName().toString());
                }
            }
            for (int k = 0; k < resolved.size(); k++) {
                MixinConfigScanner.registerMixinOwnersFromConfigs(
                        modId, List.of(resolved.get(k)), List.of(relative.get(k)));
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static List<Path> filterNonPromoted(Manifest m, List<Path> libs,
                                                Map<String, Manifest.Library> selected) {
        List<Path> out = new ArrayList<>(libs.size());
        List<Manifest.Library> declared = m.libraries();
        for (int i = 0; i < declared.size(); i++) {
            String stem = StdlibPromotion.stemOf(declared.get(i).coords());
            if (!selected.containsKey(stem)) out.add(libs.get(i));
        }
        return out;
    }

    private static Manifest readManifest(Path modFile, Path manifestResource, String modId) {
        try {
            // Dev runs: `modFile` is a source-set output dir; `manifestResource` points at
            // the unified resources view (covering `build/resources/main/...`). Production:
            // `modFile` is a jar and `manifestResource` is a ZIP-filesystem path pointing
            // at META-INF/mcdepprovider.toml inside the same jar. Either way — just read it.
            if (manifestResource != null && Files.exists(manifestResource)) {
                try (InputStream in = Files.newInputStream(manifestResource)) {
                    return ManifestIo.read(in);
                }
            }
            // Fallback: open the jar ourselves. Only valid when modFile is a regular file.
            if (!Files.isDirectory(modFile)) {
                try (var fs = java.nio.file.FileSystems.newFileSystem(modFile, (ClassLoader) null);
                     InputStream in = Files.newInputStream(fs.getPath(MANIFEST_PATH))) {
                    return ManifestIo.read(in);
                }
            }
            throw new IOException("no " + MANIFEST_PATH + " found for mod " + modId
                    + " at " + modFile);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "mcdepprovider: " + modId + " is missing or has a corrupt " + MANIFEST_PATH, e);
        }
    }

    private static List<Path> downloadLibs(Manifest manifest, String modId) {
        try {
            return CONSUMER.resolveAll(manifest);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "mcdepprovider: failed to resolve libraries for " + modId, e);
        }
    }

    /**
     * Discover the entry class via {@code @Mod("modid")} annotation in the mod's scan results —
     * the same mechanism FML's vanilla {@code javafmlmod} loader uses. Mods declare exactly as
     * they would on a regular NeoForge mod; the only difference vs vanilla is
     * {@code modLoader = "mcdepprovider"} in {@code neoforge.mods.toml}.
     */
    private static Class<?> loadEntryClass(ModFileScanData scanResults, ModClassLoader loader, String modId) {
        String fqn = scanResults.getAnnotatedBy(Mod.class, ElementType.TYPE)
                .filter(a -> modId.equals(a.annotationData().get("value")))
                .map(a -> a.clazz().getClassName())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "mcdepprovider: no @Mod(\"" + modId + "\")-annotated class found for "
                                + modId + ". Add @Mod(\"" + modId + "\") to your entry class."));
        try {
            return Class.forName(fqn, true, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "mcdepprovider: @Mod-annotated class not loadable for " + modId + ": " + fqn, e);
        }
    }

    /**
     * Dev-mode-only: expand a single mod path into the typical Gradle source-set output dir set
     * (build/resources/main + build/classes/&lt;lang&gt;/main + bridge-codegen output). The input
     * may be {@code build/classes/<lang>/main} (NeoForge MDG hands us the lang-specific classes
     * dir), {@code build/resources/main} (Fabric Loom's typical pick), or a jar file (production).
     * We walk up the path until we find a {@code build} dir, then enumerate every standard
     * source-set output under it. Production jars: returns [input].
     */
    private static List<Path> expandDevRoots(Path modFile) {
        List<Path> out = new ArrayList<>();
        out.add(modFile);
        if (modFile.getFileSystem() != java.nio.file.FileSystems.getDefault()) return out;
        // Find the project's `build/` dir by walking up. Match by name; bail at filesystem root.
        Path build = modFile;
        while (build != null && !"build".equals(String.valueOf(build.getFileName()))) {
            build = build.getParent();
        }
        if (build == null) return out;
        // Include resources/main (where META-INF lives in Loom-style layouts) + every
        // classes/<lang>/main (java, scala, kotlin, etc.) + bridge-codegen output.
        Path resourcesMain = build.resolve("resources").resolve("main");
        if (java.nio.file.Files.isDirectory(resourcesMain) && !out.contains(resourcesMain)) {
            out.add(resourcesMain);
        }
        Path classes = build.resolve("classes");
        if (java.nio.file.Files.isDirectory(classes)) {
            try (var stream = java.nio.file.Files.newDirectoryStream(classes)) {
                for (Path lang : stream) {
                    Path candidate = lang.resolve("main");
                    if (java.nio.file.Files.isDirectory(candidate) && !out.contains(candidate)) {
                        out.add(candidate);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        Path bridgeClasses = build.resolve("mcdp-mixin-bridges").resolve("classes");
        if (java.nio.file.Files.isDirectory(bridgeClasses) && !out.contains(bridgeClasses)) {
            out.add(bridgeClasses);
        }
        return List.copyOf(out);
    }

    // Lazy populator wiring. `LoadingModList.get()` returns null at McdpLanguageLoader.<clinit>
    // time (we tested — FML constructs the LanguageProviderLoader before populating
    // LoadingModList.INSTANCE). So we can't eagerly walk now. Instead we INSTALL a populator
    // into McdpProvider that fires on the first registry miss. By the time a rewritten mixin's
    // <clinit> calls resolveAutoBridgeImpl during Bootstrap.bootStrap, LoadingModList IS
    // populated even though FML hasn't reached its loadMod dispatch phase yet. The populator
    // walks the list and runs ensureRegistered() for every mcdepprovider mod, populating both
    // the per-mod ModClassLoader (via LoaderCoordinator) and the bridge registry.
    //
    // FML still calls our loadMod later for each mod; ensureRegistered is idempotent via the
    // REGISTERED cache, so loadMod is a no-op for mods the populator already handled.
    static {
        McdpProvider.installLazyPopulator(() -> {
            LoadingModList list = LoadingModList.get();
            if (list == null) return;
            for (IModInfo info : list.getMods()) {
                if (!LANGUAGE_ID.equals(info.getLoader().name())) continue;
                try {
                    ensureRegistered(info);
                } catch (Throwable t) {
                    LOG.warn("mcdepprovider: lazy-register failed for {}; loadMod will retry: {}",
                            info.getModId(), t.getMessage());
                }
            }
        });
    }
}

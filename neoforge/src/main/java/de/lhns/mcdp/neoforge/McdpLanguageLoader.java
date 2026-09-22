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
        // Idempotent path: classloader + bridges may already be registered if the lazy populator
        // installed at the bottom of this class walked LoadingModList first (the only way mods
        // whose mixins fire during MC's Bootstrap.bootStrap can register before Blocks.<clinit>).
        // loadMod is still called by FML; we just skip the redundant work.
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
     * One lock per modId, so the body below runs once even when both entry points reach it
     * concurrently — FML drives mod loading on worker threads while the lazy populator can fire
     * from a mixin's {@code <clinit>}. Not {@code REGISTERED.computeIfAbsent}: the body registers
     * into other maps and walks the whole mod list, and ConcurrentHashMap forbids a mapping
     * function that touches the same map.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> REGISTRATION_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Registrations whose body threw after the mod's loader was already registered, which makes
     * them unretryable. See {@link #ensureRegistered}.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, RuntimeException>
            FAILED_AFTER_REGISTER = new java.util.concurrent.ConcurrentHashMap<>();


    /**
     * Build the per-mod {@link ModClassLoader}, register the mod with {@link McdpProvider}, and
     * register its auto-bridge manifest. Idempotent — second call for the same modId returns
     * the cached {@link Registered} record without rerunning manifest reads or library downloads.
     *
     * <p>This method is called from two places:
     * <ol>
     *   <li>The lazy populator installed at the bottom of this class, which walks {@link
     *       LoadingModList} on the first auto-bridge registry miss during early FML init. Mods
     *       whose mixins fire during MC's {@code Bootstrap.bootStrap} (i.e. before FML reaches
     *       the {@link #loadMod} dispatch phase) need their bridges registered before
     *       {@code Blocks.<clinit>} runs.
     *   <li>{@link #loadMod} itself, which FML calls per-mod after MC bootstrap. For mods the
     *       populator already walked, this is a cache hit; for mods that arrived too late for it
     *       (e.g. PLUGIN-layer libraries discovered post-init), this is the canonical
     *       registration.
     * </ol>
     */
    private static Registered ensureRegistered(IModInfo info) {
        String modId = info.getModId();
        Registered cached = REGISTERED.get(modId);
        if (cached != null) return cached;
        synchronized (REGISTRATION_LOCKS.computeIfAbsent(modId, k -> new Object())) {
            cached = REGISTERED.get(modId);
            if (cached != null) return cached;
            RuntimeException earlier = FAILED_AFTER_REGISTER.get(modId);
            if (earlier != null) {
                throw new IllegalStateException("mcdepprovider: registration of " + modId
                        + " already failed; its first failure is the cause", earlier);
            }
            try {
                return registerNow(info, modId);
            } catch (RuntimeException e) {
                // Once the body got as far as registering the loader, a retry can never succeed:
                // McdpProvider and LoaderCoordinator reject a second registration of the same
                // modId. Remember the real failure so the retry reports it — the lazy populator
                // retries by design — instead of an "already registered" that hides the cause.
                // A failure before that point (a transient download error) stays retryable.
                if (McdpProvider.loaderFor(modId) != null) FAILED_AFTER_REGISTER.put(modId, e);
                throw e;
            }
        }
    }

    /**
     * The body of {@code ensureRegistered}, called under that mod's registration lock.
     * <p>
     * It must not run twice for one modId: {@link McdpProvider#registerMod(String, ModClassLoader)}
     * and the coordinator reject a second registration of the same modId, so a second run fails —
     * and before they did, it silently split {@code Class} identity for that mod's auto-bridges,
     * which resolve through {@code McdpProvider}.
     */
    private static Registered registerNow(IModInfo info, String modId) {

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
        List<Path> reducedLibs = StdlibPromotion.filterNonPromoted(manifest, libs, selected);
        ClassLoader libParent = promotedLoader != null
                ? promotedLoader
                : McdpLanguageLoader.class.getClassLoader();

        // Source-set output dirs the manifest captured at build time. In production jars these
        // are absolute build-machine paths that don't exist on the consumer's filesystem; we
        // filter those out and fall back to the loader's getFilePath().
        List<Path> manifestDevRoots = manifest.devRoots().stream()
                .map(Path::of)
                .filter(Files::isDirectory)
                .toList();
        List<Path> modPaths = manifestDevRoots.isEmpty()
                ? List.of(modFile)
                : manifestDevRoots;
        ModClassLoader loader = COORDINATOR.register(
                modId, reducedManifest, modPaths, reducedLibs, libParent);
        McdpProvider.registerMod(modId, loader);

        Path manifestToml = info.getOwningFile().getFile()
                .findResource("META-INF/mcdp-bridges.toml");
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
        REGISTERED.put(modId, reg);
        return reg;
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
            LoadingModList list = LoadingModList.get();
            if (list == null) {
                // Unreachable in a real boot — loadMod and the lazy populator both run after FML
                // has built the list — so this is a unit test or an FML change. Promotion needs
                // every mod's manifest, so without the list the only safe choice is none at all.
                LOG.warn("mcdepprovider: LoadingModList is not built; stdlib promotion is disabled");
                promotionSelection = Collections.emptyMap();
                return;
            }

            Map<String, Manifest.Library> selected = selectPromotions(list.getMods());
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
     * The promotion selection over FML's mod list: one winning {@link Manifest.Library} per
     * promoted stem, across every mcdepprovider mod whose manifest can take part.
     *
     * <p>A mod whose manifest cannot be read — corrupt TOML, a wrongly-typed field, a short
     * SHA-256, a coordinate without a version — is left out with a WARN naming it, and nothing
     * else changes. It used to take everyone down with it: the whole walk sat under one
     * {@code catch (Throwable)} that fell back to an empty selection, so a single bad manifest
     * silently turned promotion off for every mod. The excluded mod still fails loudly, in its
     * own {@link #registerNow}, which reads the same manifest.
     *
     * <p>Each manifest is also run through {@link StdlibPromotion#selectPromotions} on its own:
     * that is where a versionless coordinate throws, and doing it per mod keeps the throw
     * attributable to the mod that caused it rather than to the combined selection below.
     * Package-private for tests.
     */
    static Map<String, Manifest.Library> selectPromotions(List<? extends IModInfo> mods) {
        List<Manifest> manifests = new ArrayList<>();
        for (IModInfo info : mods) {
            String modId = info.getModId();
            try {
                if (!isMcdpMod(info)) continue;
                Path modFile = info.getOwningFile().getFile().getFilePath();
                Path resource = info.getOwningFile().getFile().findResource(MANIFEST_PATH);
                Manifest manifest = readManifest(modFile, resource, modId);
                PROMOTION_POLICY.selectPromotions(List.of(manifest));
                manifests.add(manifest);
            } catch (RuntimeException e) {
                LOG.warn("mcdepprovider: leaving {} out of stdlib promotion, its manifest is "
                        + "unusable (its own registration will fail with the details): {}",
                        modId, e.toString());
            }
        }
        return PROMOTION_POLICY.selectPromotions(manifests);
    }

    /** Whether FML matched this mod to our language loader. Package-private for tests. */
    static boolean isMcdpMod(IModInfo info) {
        return LANGUAGE_ID.equals(info.getLoader().name());
    }

    /**
     * Pre-register mixin class FQNs → owning modId from this mod's {@code neoforge.mods.toml}
     * {@code [[mixins]]} entries (ADR-0008 path 2). Uses FML's own {@link IConfigurable} view so we
     * don't re-parse the TOML. Silent on any failure — the annotation {@code modId} path (path 1)
     * stays primary.
     *
     * @return the FQNs registered, for tests; empty when nothing was
     */
    static List<String> registerMixinOwnersForNeoForgeMod(IModInfo info, String modId) {
        List<String> registered = new ArrayList<>();
        try {
            IConfigurable fileConfig = info.getOwningFile().getConfig();
            if (fileConfig == null) return registered;
            List<String> configPaths = new ArrayList<>();
            for (IConfigurable entry : fileConfig.getConfigList("mixins")) {
                entry.<String>getConfigElement("config").ifPresent(configPaths::add);
            }
            if (configPaths.isEmpty()) return registered;

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
                registered.addAll(MixinConfigScanner.registerMixinOwnersFromConfigs(
                        modId, List.of(resolved.get(k)), List.of(relative.get(k))));
            }
        } catch (IllegalStateException | IllegalArgumentException | ClassCastException ignored) {
            // FML IConfigurable surface — wrong types or missing keys throw these.
        }
        return registered;
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
            return CONSUMER.resolveAll(manifest, new LoggingProgressListener(LOG, modId));
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
     * The lazy populator's body: walk {@link LoadingModList} and run {@link #ensureRegistered}
     * for every mcdepprovider mod, populating both the per-mod ModClassLoader (via
     * LoaderCoordinator) and the bridge registry. Package-private for tests.
     *
     * <p>A list that is not built yet is <em>thrown</em>, not returned from: McdpProvider marks
     * its populator as done only when it returns normally, so a quiet return here would make
     * every later bridge-registry miss report "no auto-bridge registered" instead of populating
     * the registry once the list exists. Thrown, the populator stays retryable.
     */
    static void populateFromLoadingModList() {
        LoadingModList list = LoadingModList.get();
        if (list == null) {
            throw new IllegalStateException("mcdepprovider: LoadingModList is not built yet; "
                    + "the bridge registry will be populated on the next miss");
        }
        for (IModInfo info : list.getMods()) {
            if (!isMcdpMod(info)) continue;
            try {
                ensureRegistered(info);
            } catch (Throwable t) {
                LOG.warn("mcdepprovider: lazy-register failed for {}; loadMod will retry: {}",
                        info.getModId(), t.getMessage());
            }
        }
    }

    // Lazy populator wiring. `LoadingModList.get()` returns null at McdpLanguageLoader.<clinit>
    // time (we tested — FML constructs the LanguageProviderLoader before populating
    // LoadingModList.INSTANCE). So we can't eagerly walk now. Instead we INSTALL a populator
    // into McdpProvider that fires on the first registry miss. By the time a rewritten mixin's
    // <clinit> calls resolveAutoBridgeImpl during Bootstrap.bootStrap, LoadingModList IS
    // populated even though FML hasn't reached its loadMod dispatch phase yet.
    //
    // FML still calls our loadMod later for each mod; ensureRegistered is idempotent via the
    // REGISTERED cache, so loadMod is a no-op for mods the populator already handled.
    static {
        McdpProvider.installLazyPopulator(McdpLanguageLoader::populateFromLoadingModList);
    }
}

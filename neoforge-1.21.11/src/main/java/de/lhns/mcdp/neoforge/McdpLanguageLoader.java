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
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageLoader;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;

import java.lang.annotation.ElementType;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * NeoForge language loader for the MC 1.21.11 band (NeoForge 21.11.x / FML loader 10.0.x).
 * Registered via
 * {@code META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader}.
 *
 * <p>Ported from {@code neoforge/} (the 1.21.1 band, FML 4.0.x). The
 * {@code IModLanguageLoader} / {@code IModInfo} / {@code ModFileScanData} / {@code ModContainer}
 * surfaces are byte-for-byte identical between FML 4.0.42 and 10.0.36; the band exists because of
 * exactly two breaking changes in the surrounding SPI (see ADR-0030):
 * <ol>
 *   <li>{@code IModFile.findResource(String...)} is gone. FML 10 replaced the
 *       {@code cpw.mods.jarhandling.SecureJar} view with
 *       {@link net.neoforged.fml.jarcontents.JarContents}, which is stream-addressed and has
 *       no {@link Path} view of a jar entry at all. Every mod-jar resource read in this file
 *       therefore goes through {@link JarContents#containsFile}/{@link JarContents#openFile}
 *       and hands the *content* to core, via the content-addressed overloads added for this
 *       band ({@code McdpProvider.registerAutoBridgeManifestTomlContent},
 *       {@code MixinConfigScanner.registerMixinOwnersFromConfigContents}).</li>
 *   <li>{@code FMLEnvironment.dist} (a public static field) became
 *       {@code FMLEnvironment.getDist()} — handled in {@link McdpModContainer}.</li>
 * </ol>
 *
 * <p>Everything else — the LIBRARY jar type, the PLUGIN-layer ServiceLoader discovery, the lazy
 * populator's {@link LoadingModList} walk, {@code AutomaticEventSubscriber.inject} — is unchanged.
 * The jar carrying this class must declare {@code FMLModType: LIBRARY} and must <em>not</em>
 * ship a {@code neoforge.mods.toml}. Mods opt in via {@code modLoader = "mcdepprovider"}.
 */
public final class McdpLanguageLoader implements IModLanguageLoader {

    public static final String LANGUAGE_ID = "mcdepprovider";
    private static final String LANGUAGE_VERSION = "1";
    private static final String MANIFEST_PATH = "META-INF/mcdepprovider.toml";
    private static final String BRIDGES_PATH = "META-INF/mcdp-bridges.toml";

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
     * Build the per-mod {@link ModClassLoader}, register the mod with {@link McdpProvider}, and
     * register its auto-bridge manifest. Idempotent — second call for the same modId returns
     * the cached {@link Registered} record without rerunning manifest reads or library downloads.
     *
     * <p>Called both from the lazy populator (early FML init, before {@code Bootstrap.bootStrap})
     * and from {@link #loadMod}; see the canonical 1.21.1 adapter for the full rationale.
     */
    private static Registered ensureRegistered(IModInfo info) {
        String modId = info.getModId();
        Registered cached = REGISTERED.get(modId);
        if (cached != null) return cached;

        IModFile modJar = info.getOwningFile().getFile();
        JarContents contents = modJar.getContents();
        Path modFile = modJar.getFilePath();
        Manifest manifest = readManifest(contents, modFile, modId);
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

        // FML 10 has no Path view of a jar entry, so the bridge manifest is read as text and
        // handed to core's content-addressed overload rather than as a Path.
        String bridgeToml = readOptionalText(contents, BRIDGES_PATH);
        if (bridgeToml == null) {
            LOG.info("mcdepprovider: no auto-bridge manifest file for {} "
                    + "(mod has no mixins, or the jar has no {})", modId, BRIDGES_PATH);
        } else {
            LOG.info("mcdepprovider: scanning {}!{} for {} ({} chars)",
                    modFile, BRIDGES_PATH, modId, bridgeToml.length());
            int registered = McdpProvider.registerAutoBridgeManifestTomlContent(
                    loader, bridgeToml, modFile + "!" + BRIDGES_PATH);
            LOG.info("mcdepprovider: registered {} auto-bridge entries for {}", registered, modId);
        }
        registerMixinOwnersForNeoForgeMod(info, contents, modId);

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
                    IModFile modJar = info.getOwningFile().getFile();
                    try {
                        allManifests.add(readManifest(
                                modJar.getContents(), modJar.getFilePath(), info.getModId()));
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
     * don't re-parse the TOML, then reads each config's JSON out of {@link JarContents}. Silent on
     * any failure — the annotation {@code modId} path (path 1) stays primary.
     */
    private static void registerMixinOwnersForNeoForgeMod(IModInfo info, JarContents contents,
                                                          String modId) {
        try {
            IConfigurable fileConfig = info.getOwningFile().getConfig();
            if (fileConfig == null) return;
            List<String> configPaths = new ArrayList<>();
            for (IConfigurable entry : fileConfig.getConfigList("mixins")) {
                entry.<String>getConfigElement("config").ifPresent(configPaths::add);
            }
            if (configPaths.isEmpty()) return;

            List<String> configContents = new ArrayList<>(configPaths.size());
            for (String cfg : configPaths) {
                String text = readOptionalText(contents, cfg);
                if (text != null) configContents.add(text);
            }
            if (configContents.isEmpty()) return;
            MixinConfigScanner.registerMixinOwnersFromConfigContents(modId, configContents);
        } catch (IllegalStateException | IllegalArgumentException | ClassCastException ignored) {
            // FML IConfigurable surface — wrong types or missing keys throw these.
        }
    }

    /**
     * Read a mod-jar entry as UTF-8 text, or {@code null} when it is absent or unreadable.
     * The FML 10 stand-in for {@code IModFile.findResource(name)} + {@code Files.readString}.
     */
    private static String readOptionalText(JarContents contents, String name) {
        try {
            if (!contents.containsFile(name)) return null;
            return new String(contents.readFile(name), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
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

    /**
     * Read {@code META-INF/mcdepprovider.toml} out of the mod's {@link JarContents}, which
     * abstracts over dev runs (source-set output directories) and production jars alike. The
     * {@code modFile} fallback only fires if {@code JarContents} does not see the entry but the
     * jar on disk has it.
     */
    private static Manifest readManifest(JarContents contents, Path modFile, String modId) {
        try {
            if (contents != null && contents.containsFile(MANIFEST_PATH)) {
                try (InputStream in = contents.openFile(MANIFEST_PATH)) {
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

    // Lazy populator wiring — identical to the 1.21.1 band. `LoadingModList.get()` returns null
    // at McdpLanguageLoader.<clinit> time, so we install a populator into McdpProvider that fires
    // on the first registry miss; by then LoadingModList IS populated even though FML hasn't
    // reached its loadMod dispatch phase. ensureRegistered is idempotent via REGISTERED, so the
    // later loadMod call is a no-op for mods the populator already handled.
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

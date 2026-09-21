package de.lhns.mcdp.forge;

import de.lhns.mcdp.api.McdpProvider;
import de.lhns.mcdp.core.LoaderCoordinator;
import de.lhns.mcdp.core.ModClassLoader;
import de.lhns.mcdp.core.StdlibPromotion;
import de.lhns.mcdp.deps.LibraryCache;
import de.lhns.mcdp.deps.Manifest;
import de.lhns.mcdp.deps.ManifestConsumer;
import de.lhns.mcdp.deps.ManifestIo;
import de.lhns.mcdp.deps.ProgressListener;
import net.minecraftforge.forgespi.language.ILifecycleEvent;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.language.IModLanguageProvider;
import net.minecraftforge.forgespi.language.ModFileScanData;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Forge {@link IModLanguageProvider} for mcdp on MC 1.18.x (forgespi 4.0.x) and
 * MC 1.20.x (forgespi 7.x); this source is shared by both bands.
 * Discovered by Forge via
 * {@code META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider}.
 * Mods opt in by setting {@code modLoader = "mcdepprovider"} in their {@code mods.toml}.
 *
 * <p>Lifecycle (per Forge FML's invocation order, identical on both bands):
 * <ol>
 *   <li>FML scans every mod jar's class file annotations into a {@link ModFileScanData}
 *       and invokes {@link #getFileVisitor()} once per mod jar that declares
 *       {@code modLoader = "mcdepprovider"}. The visitor reads the {@code @Mod}-annotated
 *       entry class out of the scan data and registers an {@link IModLanguageLoader} for
 *       that mod ID.</li>
 *   <li>FML then calls {@link IModLanguageLoader#loadMod(IModInfo, ModFileScanData,
 *       ModuleLayer)} per mod. We construct a {@link McdpModContainer} which extends
 *       {@code net.minecraftforge.fml.ModContainer} and serves as FML's handle on
 *       the mod for lifecycle events.</li>
 * </ol>
 *
 * <p>This class also owns all <em>per-mod registration</em> state — the
 * {@link LoaderCoordinator}, the library cache, the ADR-0010 stdlib-promotion selection and
 * the {@code modId -> (ModClassLoader, Manifest)} cache. {@link #ensureRegistered(IModInfo)} is
 * the single idempotent entry point, reached from two directions (ADR-0028):
 * <ol>
 *   <li>the {@link McdpModContainer} constructor, during FML's {@code loadMod} sweep; and</li>
 *   <li>the {@link McdpProvider#installLazyPopulator(Runnable) lazy populator} installed in this
 *       class's static initializer, which fires on the first auto-bridge registry miss — i.e.
 *       from a rewritten mixin's {@code <clinit>} during MC's {@code Bootstrap.bootStrap()},
 *       long before FML reaches {@code loadMod}.</li>
 * </ol>
 *
 * <p>Entry-class instantiation and mod-bus event delivery happen later, at the FML lifecycle
 * stages — see {@link McdpModContainer} (ADR-0027), whose Javadoc also records which band is
 * runtime-verified.
 */
public final class McdpLanguageProvider implements IModLanguageProvider {

    private static final Logger LOG = Logger.getLogger("mcdepprovider");

    public static final String LANGUAGE_ID = "mcdepprovider";
    private static final String MOD_ANNOTATION_DESC = "Lnet/minecraftforge/fml/common/Mod;";

    static final String MANIFEST_PATH = "META-INF/mcdepprovider.toml";
    private static final String BRIDGE_MANIFEST_PATH = "META-INF/mcdp-bridges.toml";

    /**
     * {@code net.minecraftforge.fml.loading.LoadingModList} lives in the {@code fmlloader}
     * artifact, which is on <em>neither</em> band's compile classpath (verified: the class is
     * absent from fmlcore 1.18.2-40.3.12 and 1.20.1-47.4.20, and from forgespi 4.0.11 / 7.1.6).
     * It is present at runtime on FML's boot layer, so we reach it reflectively. See ADR-0028.
     */
    private static final String LOADING_MOD_LIST_FQN = "net.minecraftforge.fml.loading.LoadingModList";

    private static final LoaderCoordinator COORDINATOR =
            new LoaderCoordinator(McdpLanguageProvider.class.getClassLoader());
    private static final LibraryCache CACHE = LibraryCache.defaultCache();
    private static final ManifestConsumer CONSUMER = new ManifestConsumer(CACHE);

    private static final StdlibPromotion PROMOTION_POLICY = StdlibPromotion.defaults();
    private static final Object PROMOTION_LOCK = new Object();
    private static volatile Map<String, Manifest.Library> promotionSelection;
    private static volatile ClassLoader promotedLoader;

    /** (loader, manifest) cached per modId once {@link #ensureRegistered} has run. */
    record Registered(ModClassLoader loader, Manifest manifest) {}

    private static final ConcurrentHashMap<String, Registered> REGISTERED = new ConcurrentHashMap<>();

    /**
     * One lock per modId, so the body of {@link #ensureRegistered} runs once even when both of
     * ADR-0028's entry points reach it concurrently — FML drives {@code loadMod} on
     * modloading-worker threads while the lazy populator can fire from a mixin's {@code <clinit>}.
     * Not {@code REGISTERED.computeIfAbsent}: the body registers into other maps and walks the
     * whole mod list, and ConcurrentHashMap forbids a mapping function that touches the same map.
     */
    private static final ConcurrentHashMap<String, Object> REGISTRATION_LOCKS = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return LANGUAGE_ID;
    }

    @Override
    public Consumer<ModFileScanData> getFileVisitor() {
        // The scanData annotation set lists every annotation found across the mod jar; the
        // @Mod-annotated class is the mod's entry point and its annotation value is the mod ID.
        return scanData -> scanData.getAnnotations().stream()
                .filter(a -> MOD_ANNOTATION_DESC.equals(a.annotationType().getDescriptor()))
                .findFirst()
                .ifPresent(a -> {
                    String fqn = a.clazz().getClassName();
                    Object value = a.annotationData().get("value");
                    String modId = (value instanceof String s) ? s : fqn;
                    LOG.info("mcdepprovider: discovered @Mod entry " + fqn + " for modId " + modId);
                    // FML reads getTargets() per mod and dispatches loadMod through the loader
                    // registered here for that mod ID.
                    scanData.addLanguageLoader(Map.of(modId, new McdpModLanguageLoader()));
                });
    }

    @Override
    public <R extends ILifecycleEvent<R>> void consumeLifecycleEvent(Supplier<R> consumeEvent) {
        // No-op — mcdp doesn't currently hook FML's per-language lifecycle phases.
    }

    // ---------------------------------------------------------------------------------------
    // Per-mod registration (ADR-0028)
    // ---------------------------------------------------------------------------------------

    /**
     * Read the mod's manifest, resolve its libraries, build the per-mod {@link ModClassLoader},
     * register the mod with {@link McdpProvider} and register its auto-bridge manifest.
     * Idempotent — a second call for the same modId returns the cached {@link Registered} record
     * without rerunning manifest reads or library downloads.
     *
     * <p>Libraries selected for ADR-0010 stdlib promotion are stripped from the per-mod URL list
     * and served instead by one process-wide parent loader; see
     * {@link #ensurePromotionInitialized()}.
     */
    static Registered ensureRegistered(IModInfo info) {
        String modId = info.getModId();
        Registered cached = REGISTERED.get(modId);
        if (cached != null) return cached;
        synchronized (REGISTRATION_LOCKS.computeIfAbsent(modId, k -> new Object())) {
            cached = REGISTERED.get(modId);
            if (cached != null) return cached;
            return registerNow(info, modId);
        }
    }

    /**
     * The body of {@link #ensureRegistered}, called under that mod's registration lock.
     * <p>
     * It must not run twice for one modId: it ends in a plain
     * {@link McdpProvider#registerMod(String, ModClassLoader)} put, so two concurrent runs would
     * leave {@code McdpProvider} holding one {@code ModClassLoader} and {@link #REGISTERED} the
     * other — splitting {@code Class} identity for that mod's auto-bridges, which resolve through
     * {@code McdpProvider}.
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
                PROMOTION_POLICY.stripPromoted(manifest, selected),
                manifest.devRoots());
        List<Path> reducedLibs = StdlibPromotion.filterNonPromoted(manifest, libs, selected);
        ClassLoader libParent = promotedLoader != null
                ? promotedLoader
                : McdpLanguageProvider.class.getClassLoader();

        // Source-set output dirs the manifest captured at build time. In production jars these
        // are absolute build-machine paths that don't exist on the consumer's filesystem; we
        // filter those out and fall back to the mod file itself.
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

        // Bridge manifest registration (ADR-0019). Absent = mod has no mixins, or generated no
        // cross-classloader bridges; registerAutoBridgeManifestToml tolerates null/missing.
        Path bridgeToml = info.getOwningFile().getFile().findResource(BRIDGE_MANIFEST_PATH);
        int bridges = McdpProvider.registerAutoBridgeManifestToml(loader, bridgeToml);
        if (bridges > 0) {
            LOG.info("mcdepprovider: registered " + bridges + " auto-bridge entries for " + modId);
        }

        Registered reg = new Registered(loader, manifest);
        REGISTERED.put(modId, reg);
        return reg;
    }

    /**
     * ADR-0010 cross-mod stdlib unification. FML hands us one mod at a time, but
     * {@link StdlibPromotion#selectPromotions} needs the union of every mcdp mod's manifest
     * before it can pick a winner per coordinate stem. First call walks the whole
     * {@code LoadingModList} (reflectively — see {@link #LOADING_MOD_LIST_FQN}), reads every
     * mcdp mod's manifest, selects promotions, builds one shared parent loader for the winners
     * and caches both.
     *
     * <p>If the mod list is unreachable — outside FML, or if Forge ever moves the class — we
     * degrade to an empty selection, which reproduces the pre-ADR-0028 behaviour (each mod gets
     * its own stdlib copy) rather than failing the boot.
     */
    private static void ensurePromotionInitialized() {
        if (promotionSelection != null) return;
        synchronized (PROMOTION_LOCK) {
            if (promotionSelection != null) return;
            List<IModInfo> mods = allMcdpMods();
            if (mods.isEmpty()) {
                promotionSelection = Collections.emptyMap();
                return;
            }
            List<Manifest> allManifests = new ArrayList<>(mods.size());
            for (IModInfo info : mods) {
                Path modFile = info.getOwningFile().getFile().getFilePath();
                Path resource = info.getOwningFile().getFile().findResource(MANIFEST_PATH);
                try {
                    allManifests.add(readManifest(modFile, resource, info.getModId()));
                } catch (IllegalStateException ignored) {
                    // Skip mods we can't parse; ensureRegistered fails loud when they come through.
                }
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
                // Assigned before promotionSelection below, and both are volatile: a reader that
                // sees promotionSelection set must also see promotedLoader. Reversing these two
                // hands a racing thread a promotion-stripped library list with no promoted parent,
                // i.e. NoClassDefFoundError on the very stdlib the promotion exists to share.
                promotedLoader = COORDINATOR.buildSharedLibraryLoader(jars, shas);
                LOG.info("mcdepprovider: promoted " + selected.size()
                        + " stdlib artifact(s) to a shared loader across "
                        + allManifests.size() + " mod(s): " + selected.keySet());
            }
            promotionSelection = selected;
        }
    }

    /**
     * Every {@code modLoader = "mcdepprovider"} mod FML knows about, or an empty list when the
     * mod list isn't reachable. Reflective because {@code LoadingModList} is in {@code fmlloader}
     * (see {@link #LOADING_MOD_LIST_FQN}); the elements are plain {@code IModInfo}, which
     * <em>is</em> a compile-time type from forgespi on both bands.
     */
    private static List<IModInfo> allMcdpMods() {
        try {
            Class<?> lml = loadLoadingModList();
            Method get = lml.getMethod("get");
            Object list = get.invoke(null);
            if (list == null) return List.of();
            Method getMods = lml.getMethod("getMods");
            Object raw = getMods.invoke(list);
            if (!(raw instanceof List<?> mods)) return List.of();
            List<IModInfo> out = new ArrayList<>(mods.size());
            for (Object o : mods) {
                if (o instanceof IModInfo info && isMcdpMod(info)) out.add(info);
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.log(Level.FINE, "mcdepprovider: " + LOADING_MOD_LIST_FQN
                    + " unavailable; skipping the cross-mod pass", e);
            return List.of();
        }
    }

    private static Class<?> loadLoadingModList() throws ClassNotFoundException {
        try {
            return Class.forName(LOADING_MOD_LIST_FQN, false,
                    McdpLanguageProvider.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            // mcdp's jar sits on FML's PLUGIN layer; fmlloader is on the boot layer and is
            // normally visible from there, but the thread context loader is a cheap second try.
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            if (tccl == null) throw e;
            return Class.forName(LOADING_MOD_LIST_FQN, false, tccl);
        }
    }

    /**
     * Forge's {@code IModInfo} has no {@code getLoader()} (that is a NeoForge addition), so the
     * opt-in marker is the mod file's {@code modLoader} key, surfaced by forgespi as
     * {@link IModFileInfo#requiredLanguageLoaders()}. Falls back to the presence of
     * {@link #MANIFEST_PATH} for mod files whose language spec isn't populated yet.
     */
    private static boolean isMcdpMod(IModInfo info) {
        try {
            IModFileInfo fileInfo = info.getOwningFile();
            if (fileInfo == null) return false;
            for (IModFileInfo.LanguageSpec spec : fileInfo.requiredLanguageLoaders()) {
                if (LANGUAGE_ID.equals(spec.languageName())) return true;
            }
            Path manifest = fileInfo.getFile().findResource(MANIFEST_PATH);
            return manifest != null && Files.isRegularFile(manifest);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Manifest readManifest(Path modFile, Path manifestResource, String modId) {
        try {
            // Dev runs: `modFile` is a source-set output dir; `manifestResource` points at the
            // unified resources view. Production: `modFile` is a jar and `manifestResource` is a
            // ZIP-filesystem path inside it. Either way — just read it.
            if (manifestResource != null && Files.exists(manifestResource)) {
                try (InputStream in = Files.newInputStream(manifestResource)) {
                    return ManifestIo.read(in);
                }
            }
            // Fallback: open the jar ourselves. Only valid when modFile is a regular file.
            if (modFile != null && !Files.isDirectory(modFile)) {
                try (var fs = java.nio.file.FileSystems.newFileSystem(modFile, (ClassLoader) null);
                     InputStream in = Files.newInputStream(fs.getPath(MANIFEST_PATH))) {
                    return ManifestIo.read(in);
                }
            }
            throw new IOException(modId + " declares modLoader=mcdepprovider but has no "
                    + MANIFEST_PATH);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "mcdepprovider: " + modId + " is missing or has a corrupt " + MANIFEST_PATH, e);
        }
    }

    private static List<Path> downloadLibs(Manifest manifest, String modId) {
        try {
            return CONSUMER.resolveAll(manifest, new JulProgressListener(modId));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "mcdepprovider: failed to resolve libraries for " + modId, e);
        }
    }

    /**
     * {@code java.util.logging}-backed {@link ProgressListener}, matching the per-platform
     * copies in the Fabric and NeoForge adapters line for line — the Forge adapter logs through
     * JUL (see {@link #LOG}), so this variant does too rather than dragging in a third logging
     * API. Without it, a first boot that downloads hundreds of MB is completely silent.
     *
     * <p>Deliberately <em>not</em> wired to FML's {@code ProgressMeter}: that type lives in
     * {@code net.minecraftforge.fml.loading.progress}, i.e. in {@code fmlloader}, which is on
     * neither band's compile classpath (ADR-0027, ADR-0028).
     */
    private static final class JulProgressListener implements ProgressListener {

        private final String modId;
        private long startNanos;

        JulProgressListener(String modId) {
            this.modId = modId;
        }

        @Override
        public void started(int totalLibraries, long totalBytesEstimate) {
            startNanos = System.nanoTime();
            if (totalLibraries == 0) return;
            LOG.info("mcdp[" + modId + "]: resolving " + totalLibraries
                    + (totalLibraries == 1 ? " library" : " libraries"));
            // Stderr banner so terminal users see something pre-splash. One line, regardless
            // of count — library downloads run before FML's loading screen exists.
            System.err.println("[mcdp] resolving " + totalLibraries
                    + (totalLibraries == 1 ? " library" : " libraries")
                    + " for " + modId + " (this may take a moment on first launch)");
        }

        @Override
        public void libraryStarted(int index, int total, String coords, long expectedBytes) {
            LOG.fine("mcdp[" + modId + "]: (" + index + "/" + total + ") " + coords + " ...");
        }

        @Override
        public void libraryFinished(int index, int total, String coords,
                                    long actualBytes, boolean fromCache) {
            LOG.info("mcdp[" + modId + "]: (" + index + "/" + total + ") " + coords + " "
                    + (fromCache ? "(cached)" : humanBytes(actualBytes)));
        }

        @Override
        public void finished() {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            LOG.info("mcdp[" + modId + "]: resolved in " + elapsedMs + " ms");
        }

        static String humanBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024L * 1024L) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
    }

    /**
     * Forge's inner-interface contract for per-mod construction. FML calls
     * {@link #loadMod(IModInfo, ModFileScanData, ModuleLayer)} once per mod that
     * declares {@code modLoader = "mcdepprovider"}; we return a {@link McdpModContainer}
     * (typed as {@code Object} via the SPI's generic to avoid pulling fmlcore's
     * {@code ModContainer} class into the SPI).
     */
    static final class McdpModLanguageLoader implements IModLanguageProvider.IModLanguageLoader {
        @SuppressWarnings("unchecked")
        @Override
        public <T> T loadMod(IModInfo info, ModFileScanData scanResults, ModuleLayer gameLayer) {
            String entryFqn = scanResults.getAnnotations().stream()
                    .filter(a -> MOD_ANNOTATION_DESC.equals(a.annotationType().getDescriptor()))
                    .findFirst()
                    .map(a -> a.clazz().getClassName())
                    .orElseThrow(() -> new IllegalStateException(
                            "mcdepprovider: no @Mod-annotated class in " + info.getModId()));
            // Per-mod registration (manifest read, library resolution, ModClassLoader build)
            // runs via ensureRegistered, from McdpModContainer's constructor.
            return (T) new McdpModContainer(info, entryFqn);
        }
    }

    // The lazy populator (ADR-0018/0019, ported from the NeoForge adapter by ADR-0028).
    //
    // MC's Main.main() runs Bootstrap.bootStrap() BEFORE FML reaches its loadMod dispatch phase.
    // A mixin rewritten by the mcdp-bridges plugin resolves its LOGIC_* field in <clinit>, so a
    // mixin applied to a class touched during bootstrap (Blocks, BlockBehaviour$BlockStateBase,
    // …) calls McdpProvider.resolveAutoBridgeImpl against a still-empty registry and throws
    // "no auto-bridge registered". Installing a populator here lets McdpProvider fill the
    // registry on that first miss: by then LoadingModList IS populated even though loadMod
    // hasn't run. FML still calls loadMod afterwards; ensureRegistered is idempotent via the
    // REGISTERED cache, so that second pass is a no-op for mods handled here.
    //
    // LoadingModList.get() is null at <clinit> time (FML builds the language-provider registry
    // before populating it), so we install rather than walk eagerly — same as NeoForge.
    static {
        McdpProvider.installLazyPopulator(() -> {
            for (IModInfo info : allMcdpMods()) {
                try {
                    ensureRegistered(info);
                } catch (RuntimeException | LinkageError t) {
                    LOG.log(Level.WARNING, "mcdepprovider: lazy-register failed for "
                            + info.getModId() + "; loadMod will retry", t);
                }
            }
        });
    }
}

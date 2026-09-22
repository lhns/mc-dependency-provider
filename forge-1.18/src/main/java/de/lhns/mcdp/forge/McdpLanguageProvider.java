package de.lhns.mcdp.forge;

import de.lhns.mcdp.api.McdpProvider;
import de.lhns.mcdp.core.LoaderCoordinator;
import de.lhns.mcdp.core.MixinConfigScanner;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Forge {@link IModLanguageProvider} for mcdp on MC 1.17.x and 1.18.x (forgespi 4.0.x),
 * MC 1.19.x (forgespi 6.0.x) and MC 1.20.x (forgespi 7.x); this source is shared by all four
 * Forge bands (forge-1.18 owns it, the others point their source sets here).
 * Discovered by Forge via
 * {@code META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider}.
 * Mods opt in by setting {@code modLoader = "mcdepprovider"} in their {@code mods.toml}.
 *
 * <p>Lifecycle (per Forge FML's invocation order, identical on all four bands):
 * <ol>
 *   <li>FML scans every mod jar's class file annotations into a {@link ModFileScanData}
 *       and invokes {@link #getFileVisitor()} once per mod jar that declares
 *       {@code modLoader = "mcdepprovider"}. The visitor reads every {@code @Mod}-annotated
 *       entry class out of the scan data and registers an {@link IModLanguageLoader} for
 *       each of their mod IDs — one jar may declare several mods.</li>
 *   <li>FML then calls {@link IModLanguageLoader#loadMod(IModInfo, ModFileScanData,
 *       ModuleLayer)} per mod. We pick the {@code @Mod} class whose value is that mod's ID
 *       and construct a {@link McdpModContainer} for it, which extends
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
     * Forge has no {@code [[mixins]]} block in {@code mods.toml} — the NeoForge adapters read
     * one, Forge's {@code ModFileParser} never had one to read. On Forge a mod's Mixin configs
     * are named by Mixin's own {@code MixinConfigs} jar-manifest attribute, which
     * {@code MixinPlatformAgentDefault} splits on commas.
     */
    private static final String JAR_MANIFEST_PATH = "META-INF/MANIFEST.MF";
    private static final String MIXIN_CONFIGS_ATTRIBUTE = "MixinConfigs";

    /**
     * {@code net.minecraftforge.fml.loading.LoadingModList} lives in the {@code fmlloader}
     * artifact, which is on <em>no</em> Forge band's compile classpath (verified: the class is
     * absent from fmlcore 1.18.2-40.3.12 and 1.20.1-47.4.20, and from forgespi 4.0.11 / 7.1.6).
     * fmlcore declares fmlloader as a runtime dependency only, so it <em>is</em> on each band's
     * unit-test runtime classpath — where {@code get()} returns null, nothing having built it.
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
        // The scanData annotation set lists every annotation found across the mod jar; each
        // @Mod-annotated class is one mod's entry point and its annotation value is that mod's
        // ID. A jar may declare several mods, so every @Mod gets a loader — registering only the
        // first one found left the others with no loader at all.
        return scanData -> {
            Map<String, IModLanguageProvider.IModLanguageLoader> loaders = new LinkedHashMap<>();
            for (ModFileScanData.AnnotationData a : scanData.getAnnotations()) {
                if (!isModAnnotation(a)) continue;
                String fqn = a.clazz().getClassName();
                String modId = modIdOf(a);
                LOG.info("mcdepprovider: discovered @Mod entry " + fqn + " for modId " + modId);
                loaders.put(modId, new McdpModLanguageLoader());
            }
            // FML reads getTargets() per mod and dispatches loadMod through the loader
            // registered here for that mod ID.
            if (!loaders.isEmpty()) scanData.addLanguageLoader(loaders);
        };
    }

    private static boolean isModAnnotation(ModFileScanData.AnnotationData a) {
        return MOD_ANNOTATION_DESC.equals(a.annotationType().getDescriptor());
    }

    /** The {@code @Mod} value; the class name for the (invalid) value-less annotation. */
    private static String modIdOf(ModFileScanData.AnnotationData a) {
        Object value = a.annotationData().get("value");
        return (value instanceof String s) ? s : a.clazz().getClassName();
    }

    /**
     * The entry class for {@code modId}: the {@code @Mod} class whose value is that ID. Taking
     * the first {@code @Mod} instead would hand every mod of a multi-mod jar the same (arbitrary)
     * entry class: the annotation set is in class-scan order, which says nothing about mods.
     */
    static String entryClassFor(String modId, ModFileScanData scanResults) {
        List<String> others = new ArrayList<>();
        for (ModFileScanData.AnnotationData a : scanResults.getAnnotations()) {
            if (!isModAnnotation(a)) continue;
            String id = modIdOf(a);
            if (id.equals(modId)) return a.clazz().getClassName();
            others.add(id);
        }
        throw new IllegalStateException("mcdepprovider: no @Mod(\"" + modId
                + "\")-annotated class in the mod file of " + modId
                + (others.isEmpty() ? "" : " (it declares @Mod for " + others + ")"));
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

        registerMixinOwnersFromJarManifest(info, modId);

        Registered reg = new Registered(loader, manifest);
        REGISTERED.put(modId, reg);
        return reg;
    }

    /**
     * Map every Mixin class this mod declares back to {@code modId} (ADR-0008 path 2), reading
     * the config list from the mod file's {@code MixinConfigs} jar-manifest attribute — the
     * Forge equivalent of the NeoForge adapters' {@code [[mixins]]} scan.
     *
     * <p><b>Severity: robustness, not correctness.</b> Nothing that works today starts working
     * because of this. {@code McdpProvider.loadMixinImpl} finds its caller by stack-walk and
     * resolves the loader from {@code @McdpMixin(modId = ...)} first, falling back to the
     * single-registered-mod shortcut; the ADR-0018 auto-codegen path does not consult this map
     * at all. What it closes is one real gap: with two or more mcdp mods installed, a mixin
     * whose {@code @McdpMixin} omits {@code modId} has no shortcut left and
     * {@code loadMixinImpl} throws — see
     * {@code McdpProviderTest.failsLoudlyWithMultipleModsAndNoModIdOrFqnMapping}. Fabric and
     * NeoForge authors are covered there by their loaders' config scans; Forge authors were not.
     *
     * <p><b>Production only.</b> {@link #JAR_MANIFEST_PATH} exists in a packaged mod jar and
     * nowhere else: a dev run's mod file is the exploded source-set output, which has no
     * {@code META-INF/MANIFEST.MF}, and ForgeGradle passes the config via {@code --mixin.config}
     * instead — an argument that names no mod. So a dev boot registers nothing here and silently
     * keeps the pre-existing behaviour. Best-effort throughout, like every other caller of
     * {@link MixinConfigScanner}.
     */
    private static void registerMixinOwnersFromJarManifest(IModInfo info, String modId) {
        List<String> registered = registerMixinOwnersFromModFile(
                modId, name -> findResource(info, name));
        if (!registered.isEmpty()) {
            LOG.info("mcdepprovider: mapped " + registered.size() + " mixin class(es) to "
                    + modId + " from the " + MIXIN_CONFIGS_ATTRIBUTE + " manifest attribute");
        }
    }

    /**
     * The half of {@link #registerMixinOwnersFromJarManifest} that touches no forgespi type, so
     * it can be tested against a plain directory and against a real jar's ZIP filesystem — the
     * two shapes {@code findResource} hands back in dev and in production.
     *
     * @param resourceFinder mod-file-relative resource name to a readable {@link Path}, or null
     * @return every FQN registered, in declaration order
     */
    static List<String> registerMixinOwnersFromModFile(String modId,
                                                       Function<String, Path> resourceFinder) {
        List<String> configNames = mixinConfigNames(resourceFinder.apply(JAR_MANIFEST_PATH));
        if (configNames.isEmpty()) return List.of();
        List<String> contents = new ArrayList<>(configNames.size());
        for (String name : configNames) {
            Path config = resourceFinder.apply(name);
            if (config == null) continue;
            try {
                contents.add(Files.readString(config, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException ignored) {
                // A manifest may name a config the jar doesn't carry; the others still count.
            }
        }
        return MixinConfigScanner.registerMixinOwnersFromConfigContents(modId, contents);
    }

    /** {@code MixinConfigs: a.json,b.json} → {@code [a.json, b.json]}; empty when absent. */
    static List<String> mixinConfigNames(Path jarManifest) {
        if (jarManifest == null) return List.of();
        String attribute;
        try (InputStream in = Files.newInputStream(jarManifest)) {
            attribute = new java.util.jar.Manifest(in)
                    .getMainAttributes().getValue(MIXIN_CONFIGS_ATTRIBUTE);
        } catch (IOException | RuntimeException e) {
            // findResource returns a speculative path for resources no root actually holds, so
            // "no manifest" arrives here as NoSuchFileException rather than as a null Path.
            return List.of();
        }
        if (attribute == null || attribute.isBlank()) return List.of();
        List<String> names = new ArrayList<>();
        for (String part : attribute.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    private static Path findResource(IModInfo info, String name) {
        try {
            return info.getOwningFile().getFile().findResource(name);
        } catch (RuntimeException e) {
            return null;
        }
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
            List<Manifest> allManifests = manifestsForPromotion(mods);

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
     * Every mod's manifest that can take part in the promotion selection, in {@code mods} order.
     *
     * <p>A mod whose manifest cannot be read, or whose library coordinates the selection cannot
     * parse, is left out with a warning rather than failing the whole pass. The selection is
     * computed once for everyone and cached only on success, so one broken manifest escaping
     * here would fail every mcdp mod's registration, not just its own. What reaches this method
     * is not only {@link IllegalStateException}: {@link ManifestIo} throws
     * {@link ClassCastException} for a wrongly-typed field, {@link Manifest.Library} throws
     * {@link IllegalArgumentException} for a short SHA-256, {@link StdlibPromotion#stemOf} for a
     * coordinate without a version, and the jar-filesystem fallback can throw
     * {@code ProviderNotFoundException}. The excluded mod's own {@link #ensureRegistered} call
     * reads the same manifest again and fails loudly, naming it.
     */
    static List<Manifest> manifestsForPromotion(List<IModInfo> mods) {
        List<Manifest> out = new ArrayList<>(mods.size());
        for (IModInfo info : mods) {
            String modId = modIdForLog(info);
            try {
                Path modFile = info.getOwningFile().getFile().getFilePath();
                Path resource = info.getOwningFile().getFile().findResource(MANIFEST_PATH);
                Manifest manifest = readManifest(modFile, resource, modId);
                // Selecting over this manifest alone parses every library coordinate, so a bad
                // one surfaces here, attributed to this mod, instead of inside the shared pass.
                PROMOTION_POLICY.selectPromotions(List.of(manifest));
                out.add(manifest);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "mcdepprovider: leaving " + modId
                        + " out of stdlib promotion: its " + MANIFEST_PATH
                        + " is unusable (" + e + "); its own registration will fail on it", e);
            }
        }
        return out;
    }

    /** {@code info.getModId()} for a log line, which must not itself throw. */
    private static String modIdForLog(IModInfo info) {
        try {
            return info.getModId();
        } catch (RuntimeException e) {
            return "<mod with unreadable id>";
        }
    }

    /**
     * Every {@code modLoader = "mcdepprovider"} mod FML knows about, or an empty list when the
     * mod list isn't reachable. Reflective because {@code LoadingModList} is in {@code fmlloader}
     * (see {@link #LOADING_MOD_LIST_FQN}); the elements are plain {@code IModInfo}, which
     * <em>is</em> a compile-time type from forgespi on every band.
     */
    static List<IModInfo> allMcdpMods() {
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
    static boolean isMcdpMod(IModInfo info) {
        try {
            IModFileInfo fileInfo = info.getOwningFile();
            if (fileInfo == null) return false;
            for (IModFileInfo.LanguageSpec spec : fileInfo.requiredLanguageLoaders()) {
                if (LANGUAGE_ID.equals(spec.languageName())) return true;
            }
            Path manifest = fileInfo.getFile().findResource(MANIFEST_PATH);
            return manifest != null && Files.isRegularFile(manifest);
        } catch (RuntimeException e) {
            // Not silent: if this is an mcdp mod, it now drops out of the promotion selection
            // while stripPromoted still strips its stdlib stems by name — so it runs against
            // whatever promoted version the other mods picked, possibly older than its own.
            LOG.log(Level.WARNING, "mcdepprovider: could not tell whether " + modIdForLog(info)
                    + " is an mcdp mod; leaving it out of stdlib promotion (if it is one, it"
                    + " will run on the stdlib version selected for the other mods)", e);
            return false;
        }
    }

    static Manifest readManifest(Path modFile, Path manifestResource, String modId) {
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
     * no Forge band's compile classpath (ADR-0027, ADR-0028).
     */
    static final class JulProgressListener implements ProgressListener {

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
            // Locale.ROOT: the default locale would log "1,5 KB" on a de_DE machine.
            if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
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
            String entryFqn = entryClassFor(info.getModId(), scanResults);
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

package de.lhns.mcdp.api;

import de.lhns.mcdp.core.ModClassLoader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public facade for mods. Lives under {@code de.lhns.mcdp.api.*} — a platform-prefix
 * that {@link ModClassLoader} always delegates parent-first (so calls from Mixin-transformed code
 * and from per-mod code both resolve to the same {@code Class}).
 * <p>
 * Populated by the platform adapters (NeoForge {@code McdpLanguageLoader}, Fabric
 * {@code McdpLanguageAdapter}) as mods are discovered.
 */
public final class McdpProvider {

    private McdpProvider() {}

    private static final Map<String, ModClassLoader> MOD_LOADERS_BY_ID = new ConcurrentHashMap<>();
    private static final Map<String, ModClassLoader> MOD_LOADERS_BY_MIXIN_FQN = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Object> MIXIN_IMPL_CACHE = new ConcurrentHashMap<>();

    /** Auto-bridge registry: keyed by {@code "<mixinFqn>#<fieldName>"}. */
    private static final Map<String, BridgeEntry> AUTO_BRIDGE_REGISTRY = new ConcurrentHashMap<>();
    /** Resolved-impl cache for the auto-bridge path. Same key as the registry. */
    private static final Map<String, Object> AUTO_BRIDGE_IMPL_CACHE = new ConcurrentHashMap<>();

    private record BridgeEntry(String implFqn, ModClassLoader modLoader) {}

    /**
     * Register a mod's {@link ModClassLoader} so {@link #loadMixinImpl(Class)} can find it at
     * runtime. Called by platform adapters once per mod during boot.
     */
    public static void registerMod(String modId, ModClassLoader loader) {
        MOD_LOADERS_BY_ID.put(Objects.requireNonNull(modId), Objects.requireNonNull(loader));
        wireAutoMixinBridges(modId, loader);
    }

    /**
     * Register auto-bridge manifests by walking a per-mod index file. Each line of {@code
     * indexFile} is a mixin FQN; for each entry, the {@code manifestPathResolver} is asked to
     * produce a {@link java.nio.file.Path} pointing at that mixin's individual manifest
     * ({@code <fqn>.txt}). Platform adapters supply a resolver that uses their native
     * single-file lookup ({@code IModFile.findResource} on NeoForge, {@code
     * ModContainer.findPath} on Fabric) — both APIs are reliable for exact-file lookups, unlike
     * directory-style lookups which behave inconsistently on NeoForge {@code UnionPath}.
     *
     * <p>Lines whose resolver returns {@code null} or a non-existent path are skipped silently
     * (the count returned reflects only successful registrations). Re-registration of the same
     * {@code (mixin, field)} key overwrites with the new entry.</p>
     *
     * @param modLoader            per-mod loader the bridge impls belong to
     * @param indexFile            path to {@code META-INF/mcdp-mixin-bridges-index.txt}
     * @param manifestPathResolver maps a mixin FQN to the path of its {@code <fqn>.txt} manifest
     *                             (return {@code null} if the platform can't locate the file)
     * @return number of {@code (mixin, field)} entries registered
     */
    public static int registerAutoBridgeManifestsFromIndex(
            ModClassLoader modLoader,
            java.nio.file.Path indexFile,
            java.util.function.Function<String, java.nio.file.Path> manifestPathResolver) {
        Objects.requireNonNull(modLoader, "modLoader");
        Objects.requireNonNull(indexFile, "indexFile");
        Objects.requireNonNull(manifestPathResolver, "manifestPathResolver");
        if (!java.nio.file.Files.isRegularFile(indexFile)) return 0;
        int registered = 0;
        try {
            for (String line : java.nio.file.Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
                String fqn = line.trim();
                if (fqn.isEmpty()) continue;
                java.nio.file.Path manifestPath;
                try {
                    manifestPath = manifestPathResolver.apply(fqn);
                } catch (RuntimeException re) {
                    continue; // resolver might throw for absent entries on some platforms
                }
                if (manifestPath == null || !java.nio.file.Files.isRegularFile(manifestPath)) continue;
                int before = AUTO_BRIDGE_REGISTRY.size();
                registerOneManifest(
                        new ManifestData(fqn, java.nio.file.Files.readAllBytes(manifestPath)),
                        modLoader);
                registered += AUTO_BRIDGE_REGISTRY.size() - before;
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "mcdepprovider: failed to read auto-bridge index " + indexFile, e);
        }
        return registered;
    }

    /**
     * Register auto-bridge manifests scanned from an explicit content root. Platform adapters use
     * this when {@code modLoader.getURLs()} doesn't span every content root of the mod (NeoForge
     * dev: only one path lands on the per-mod loader; the resources dir holding manifests can be
     * a sibling). The adapter has access to the unified mod-content view via the platform's own
     * resource API ({@code IModFile.findResource} on NeoForge, {@code ModContainer.findPath} on
     * Fabric) and feeds the resolved {@code META-INF/mcdp-mixin-bridges/} dir here.
     *
     * <p>Idempotent: re-registering the same {@code (mixinFqn, fieldName)} key overwrites with
     * the new entry.</p>
     *
     * @deprecated Prefer {@link #registerAutoBridgeManifestsFromIndex} — directory-style
     *             {@code findResource} on NeoForge {@code UnionPath} returns speculative or null
     *             paths in dev mode, leading to silent zero-registration. The index-file API
     *             relies only on exact-file lookups, which both platforms support reliably.
     *
     * @param modLoader   the per-mod {@link ModClassLoader} that the bridge impls belong to
     * @param manifestDir a directory containing {@code <mixinFqn>.txt} manifest files; safe to
     *                    pass a non-existent path (no-op)
     * @return number of {@code (mixin, field)} entries registered (0 if the dir is empty/missing)
     */
    @Deprecated
    public static int registerAutoBridgeManifests(ModClassLoader modLoader, java.nio.file.Path manifestDir) {
        Objects.requireNonNull(modLoader, "modLoader");
        Objects.requireNonNull(manifestDir, "manifestDir");
        if (!java.nio.file.Files.isDirectory(manifestDir)) return 0;
        int registered = 0;
        try (var stream = java.nio.file.Files.list(manifestDir)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".txt")) continue;
                String fqn = name.substring(0, name.length() - ".txt".length());
                byte[] content = java.nio.file.Files.readAllBytes(p);
                int before = AUTO_BRIDGE_REGISTRY.size();
                registerOneManifest(new ManifestData(fqn, content), modLoader);
                registered += AUTO_BRIDGE_REGISTRY.size() - before;
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "mcdepprovider: failed to scan auto-bridge manifests at " + manifestDir, e);
        }
        return registered;
    }

    /**
     * Read every {@code META-INF/mcdp-mixin-bridges/<mixinFqn>.txt} resource on the per-mod
     * classloader and populate {@link #AUTO_BRIDGE_REGISTRY}. The actual impl class is loaded
     * lazily via {@link #resolveAutoBridgeImpl(String, String)} from the rewritten mixin's
     * {@code <clinit>} — Sponge Mixin forbids direct {@code Class.forName} on classes declared
     * in {@code mixins.json}, so we never touch the mixin class here.
     *
     * <p>Manifest schema (one block per bridge target, blank lines ignored):
     * <pre>
     * bridge=com.example.mod.mcdp_mixin_bridges.FooBridge
     * impl=com.example.mod.mcdp_mixin_bridges.FooBridgeImpl
     * field=LOGIC_Foo
     * </pre>
     */
    private static void wireAutoMixinBridges(String modId, ModClassLoader modLoader) {
        List<ManifestData> manifests = new ArrayList<>();
        for (URL root : modLoader.getURLs()) {
            manifests.addAll(readBridgeManifests(root));
        }
        for (ManifestData md : manifests) {
            try {
                registerOneManifest(md, modLoader);
            } catch (Throwable t) {
                throw new IllegalStateException("mcdepprovider: failed to register mixin bridge "
                        + md.mixinFqn() + " for mod '" + modId + "': " + t.getMessage(), t);
            }
        }
    }

    private record ManifestData(String mixinFqn, byte[] content) {}

    /**
     * Resolve and instantiate the bridge impl for an auto-codegen mixin's {@code LOGIC_*}
     * field. Called from the mixin's {@code <clinit>} (emitted by the gradle plugin). Returns
     * {@code Object} so the call site lives entirely on the game-layer classloader; the
     * caller {@code CHECKCAST}s to the bridge interface (which is in the mod's bridge package
     * and auto-added to {@code sharedPackages}).
     *
     * <p>Result is cached per {@code (mixinFqn, fieldName)} — second-and-later GETSTATIC of
     * the LOGIC field never reaches this method (the field hangs onto the impl), but a class
     * reload during tests can hit it again, and we want stable identity then.
     */
    public static Object resolveAutoBridgeImpl(String mixinFqn, String fieldName) {
        String key = mixinFqn + "#" + fieldName;
        Object cached = AUTO_BRIDGE_IMPL_CACHE.get(key);
        if (cached != null) return cached;
        BridgeEntry e = AUTO_BRIDGE_REGISTRY.get(key);
        if (e == null) {
            throw new IllegalStateException("mcdepprovider: no auto-bridge registered for "
                    + mixinFqn + "." + fieldName);
        }
        try {
            Class<?> implCls = Class.forName(e.implFqn(), true, e.modLoader());
            var ctor = implCls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object instance = ctor.newInstance();
            Object existing = AUTO_BRIDGE_IMPL_CACHE.putIfAbsent(key, instance);
            return existing != null ? existing : instance;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("mcdepprovider: failed to instantiate auto-bridge impl "
                    + e.implFqn() + " for " + mixinFqn + "." + fieldName, ex);
        }
    }

    private static List<ManifestData> readBridgeManifests(URL jarOrDir) {
        List<ManifestData> out = new ArrayList<>();
        try {
            String s = jarOrDir.toString();
            if (s.endsWith(".jar") || s.endsWith(".zip")) {
                // Read entries inline — never hand out a jar: URL, since URL.openStream()
                // routes through JarFileFactory's permanent cache and pins the file handle
                // on Windows (breaking @TempDir cleanup and any other file deletion).
                try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(jarOrDir.openStream())) {
                    java.util.zip.ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        String n = e.getName();
                        if (n.startsWith("META-INF/mcdp-mixin-bridges/") && n.endsWith(".txt")) {
                            String fqn = n.substring("META-INF/mcdp-mixin-bridges/".length(),
                                    n.length() - ".txt".length());
                            out.add(new ManifestData(fqn, zis.readAllBytes()));
                        }
                    }
                }
            } else {
                java.io.File dir = new java.io.File(jarOrDir.toURI());
                java.io.File bridgeDir = new java.io.File(dir, "META-INF/mcdp-mixin-bridges");
                if (bridgeDir.isDirectory()) {
                    java.io.File[] children = bridgeDir.listFiles((d, name) -> name.endsWith(".txt"));
                    if (children != null) {
                        for (java.io.File f : children) {
                            String name = f.getName();
                            String fqn = name.substring(0, name.length() - ".txt".length());
                            out.add(new ManifestData(fqn, java.nio.file.Files.readAllBytes(f.toPath())));
                        }
                    }
                }
            }
        } catch (Exception ignore) {
            // best effort; missing directories are normal
        }
        return out;
    }

    private static void registerOneManifest(ManifestData md, ModClassLoader modLoader) throws Exception {
        try (InputStream in = new java.io.ByteArrayInputStream(md.content());
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            String bridge = null, impl = null, field = null;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq), v = line.substring(eq + 1);
                switch (k) {
                    case "bridge" -> bridge = v;
                    case "impl" -> impl = v;
                    case "field" -> field = v;
                }
                if (bridge != null && impl != null && field != null) {
                    AUTO_BRIDGE_REGISTRY.put(md.mixinFqn() + "#" + field, new BridgeEntry(impl, modLoader));
                    bridge = null; impl = null; field = null;
                }
            }
        }
    }

    /**
     * Tell the provider which mod a given mixin-owning class belongs to. Platform adapters call
     * this when they parse a mod's Mixin config; it lets {@link #loadMixinImpl(Class)} skip the
     * stack-walk and look up the mod directly. Optional — the stack-walk fallback works too.
     */
    public static void registerMixinOwner(String mixinClassFqn, String modId) {
        ModClassLoader loader = MOD_LOADERS_BY_ID.get(modId);
        if (loader == null) throw new IllegalStateException("no ModClassLoader registered for modId: " + modId);
        MOD_LOADERS_BY_MIXIN_FQN.put(mixinClassFqn, loader);
    }

    /** Lookup for tests and platform adapters. */
    public static ModClassLoader loaderFor(String modId) {
        return MOD_LOADERS_BY_ID.get(modId);
    }

    /**
     * Load the implementation of a bridge interface declared in a mixin class.
     * <p>
     * Walks the stack to find the calling class (assumed to be a {@code @McdpMixin}-annotated
     * mixin), reads its {@link McdpMixin#impl()}, looks up the owning mod's
     * {@link ModClassLoader}, loads the impl class, instantiates it via its no-arg constructor,
     * and caches the result by interface type.
     * <p>
     * Typical use: {@code private static final MyLogic L = McdpProvider.loadMixinImpl(MyLogic.class);}
     */
    @SuppressWarnings("unchecked")
    public static <I> I loadMixinImpl(Class<I> iface) {
        Object cached = MIXIN_IMPL_CACHE.get(iface);
        if (cached != null) return iface.cast(cached);

        Class<?> caller = callerClass();
        if (caller == null) {
            throw new IllegalStateException("loadMixinImpl: could not determine calling class");
        }
        McdpMixin ann = caller.getAnnotation(McdpMixin.class);
        if (ann == null) {
            throw new IllegalStateException("loadMixinImpl: calling class " + caller.getName()
                    + " is missing @McdpMixin");
        }

        ModClassLoader loader = resolveLoader(caller, ann.modId());
        if (loader == null) {
            throw new IllegalStateException("loadMixinImpl: no ModClassLoader registered for mixin "
                    + caller.getName() + " (modId='" + ann.modId() + "')");
        }

        Class<?> implCls;
        try {
            implCls = Class.forName(ann.impl(), true, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("loadMixinImpl: impl class not found on mod loader: " + ann.impl(), e);
        }
        if (!iface.isAssignableFrom(implCls)) {
            throw new IllegalStateException("loadMixinImpl: impl " + implCls.getName()
                    + " does not implement " + iface.getName());
        }

        Object instance;
        try {
            var ctor = implCls.getDeclaredConstructor();
            ctor.setAccessible(true);
            instance = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("loadMixinImpl: failed to instantiate " + implCls.getName(), e);
        }

        MIXIN_IMPL_CACHE.put(iface, instance);
        return iface.cast(instance);
    }

    private static ModClassLoader resolveLoader(Class<?> mixin, String annModId) {
        if (!annModId.isEmpty()) {
            ModClassLoader byAnn = MOD_LOADERS_BY_ID.get(annModId);
            if (byAnn != null) return byAnn;
        }
        ModClassLoader byFqn = MOD_LOADERS_BY_MIXIN_FQN.get(mixin.getName());
        if (byFqn != null) return byFqn;

        // Fallback: if there's exactly one mod, it's unambiguous. Useful in single-mod dev boots.
        if (MOD_LOADERS_BY_ID.size() == 1) {
            return MOD_LOADERS_BY_ID.values().iterator().next();
        }
        return null;
    }

    /**
     * Find the first caller outside {@link McdpProvider} itself. Uses {@link StackWalker} with
     * {@link StackWalker.Option#RETAIN_CLASS_REFERENCE} so we get {@code Class} objects directly.
     */
    private static Class<?> callerClass() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(c -> c != McdpProvider.class)
                        .findFirst()
                        .orElse(null));
    }

    // --- test hooks ---

    /** Package-private (exposed for tests) — clears the impl cache and all registrations. */
    static void resetForTests() {
        MIXIN_IMPL_CACHE.clear();
        MOD_LOADERS_BY_ID.clear();
        MOD_LOADERS_BY_MIXIN_FQN.clear();
        AUTO_BRIDGE_REGISTRY.clear();
        AUTO_BRIDGE_IMPL_CACHE.clear();
    }

    /** Package-private (exposed for tests) — clears just the impl cache. */
    static void clearImplCacheForTests() {
        MIXIN_IMPL_CACHE.clear();
    }
}

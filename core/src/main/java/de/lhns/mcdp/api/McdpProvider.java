package de.lhns.mcdp.api;

import de.lhns.mcdp.core.ModClassLoader;
import de.lhns.mcdp.deps.MiniToml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    static {
        Module m = McdpProvider.class.getModule();
        ModuleLayer layer = m.getLayer();
        System.out.println("[mcdp-debug] McdpProvider.<clinit>"
                + " module=" + m.getName()
                + " layer=" + (layer == null ? "<null>" : layer.toString())
                + " classloader=" + McdpProvider.class.getClassLoader());
    }

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
     * runtime. Called by platform adapters once per mod during boot. Auto-bridge entries live in
     * a separate per-mod TOML manifest; adapters call {@link #registerAutoBridgeManifestToml}
     * after this method.
     */
    public static void registerMod(String modId, ModClassLoader loader) {
        MOD_LOADERS_BY_ID.put(Objects.requireNonNull(modId), Objects.requireNonNull(loader));
    }

    /**
     * Register auto-bridge entries from a per-mod {@code META-INF/mcdp-mixin-bridges.toml}
     * manifest emitted by the {@code mcdp-mixin-bridges} Gradle plugin. The format is documented
     * in ADR-0019: one {@code [[bridge]]} array-of-tables entry per {@code (mixin, field)} pair,
     * with keys {@code mixin}, {@code field}, {@code interface} (informational), {@code impl}.
     *
     * <p>Re-registration of the same {@code (mixin, field)} key overwrites the previous entry
     * (the registry is a {@link ConcurrentHashMap}). Returns the number of {@code [[bridge]]}
     * entries successfully registered. Missing or empty file → 0; malformed TOML → throws.</p>
     *
     * @param modLoader per-mod loader the bridge impls belong to
     * @param tomlFile  path to the manifest; safe to pass {@code null} or a non-existent path
     * @return number of bridge entries registered
     */
    public static int registerAutoBridgeManifestToml(ModClassLoader modLoader, Path tomlFile) {
        Objects.requireNonNull(modLoader, "modLoader");
        // Null path is the legitimate "mod has no mixins, no manifest emitted" signal — codegen
        // skips emission when rewrittenMixins.isEmpty(). The platform adapter logs the null case
        // separately so we can tell "intentional no-op" from "expected manifest but findResource
        // returned null". Any non-null path that fails to read throws loudly: silent fall-throughs
        // hid the UnionPath speculative-resolution bug for too long.
        if (tomlFile == null) return 0;
        String content;
        try {
            content = Files.readString(tomlFile, StandardCharsets.UTF_8);
        } catch (java.io.IOException ioe) {
            throw new IllegalStateException(
                    "mcdepprovider: failed to read auto-bridge manifest " + tomlFile
                            + " (path returned by adapter's findResource() but unreadable; "
                            + "likely a UnionPath speculative-resolution quirk on NeoForge dev)",
                    ioe);
        }
        Map<String, Object> root;
        try {
            root = MiniToml.parse(content);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "mcdepprovider: failed to parse auto-bridge manifest " + tomlFile, e);
        }
        Object bridges = root.get("bridge");
        if (!(bridges instanceof List<?> list)) return 0;
        int registered = 0;
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> table)) continue;
            String mixinFqn = asStr(table.get("mixin"));
            String field = asStr(table.get("field"));
            String impl = asStr(table.get("impl"));
            if (mixinFqn == null || field == null || impl == null) {
                throw new IllegalStateException(
                        "mcdepprovider: incomplete [[bridge]] entry in " + tomlFile + ": " + table);
            }
            AUTO_BRIDGE_REGISTRY.put(mixinFqn + "#" + field, new BridgeEntry(impl, modLoader));
            registered++;
        }
        return registered;
    }

    private static String asStr(Object o) {
        return o instanceof String s ? s : null;
    }

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
    /**
     * Lazy populator. Set by the platform adapter at its earliest possible lifecycle hook
     * (NeoForge: {@link #installLazyPopulator(Runnable)} called during static init of
     * McdpLanguageLoader). Invoked on the first registry miss in {@link #resolveAutoBridgeImpl}
     * — necessary because MC's {@code Main.main()} runs {@code Bootstrap.bootStrap()} BEFORE
     * {@code ServerModLoader.load()}; mods whose mixins fire during bootstrap (e.g.
     * mc-fluid-physics targeting {@code BlockBehaviour$BlockStateBase}) need the registry
     * populated before FML reaches its loadMod dispatch phase. By the time the first mixin
     * {@code <clinit>} calls into us, FMLLoader.LoadingModList is populated even though
     * loadMod hasn't been invoked yet.
     */
    private static volatile Runnable lazyPopulator;
    private static volatile boolean lazyPopulatorRan;

    public static void installLazyPopulator(Runnable populator) {
        lazyPopulator = populator;
    }

    public static Object resolveAutoBridgeImpl(String mixinFqn, String fieldName) {
        String key = mixinFqn + "#" + fieldName;
        Object cached = AUTO_BRIDGE_IMPL_CACHE.get(key);
        if (cached != null) return cached;
        BridgeEntry e = AUTO_BRIDGE_REGISTRY.get(key);
        if (e == null) {
            // First miss → invoke the platform adapter's eager-populate hook. Only run once;
            // subsequent misses are real (impl missing or codegen incomplete).
            Runnable p = lazyPopulator;
            if (p != null && !lazyPopulatorRan) {
                synchronized (McdpProvider.class) {
                    if (!lazyPopulatorRan) {
                        lazyPopulatorRan = true;
                        try {
                            p.run();
                        } catch (Throwable t) {
                            System.out.println("[mcdp-debug] lazy populator failed: " + t);
                            t.printStackTrace(System.out);
                        }
                    }
                }
                e = AUTO_BRIDGE_REGISTRY.get(key);
            }
        }
        if (e == null) {
            System.out.println("[mcdp-debug] resolveAutoBridgeImpl MISS key=" + key
                    + " McdpProvider.class@" + System.identityHashCode(McdpProvider.class)
                    + " (loaded by " + McdpProvider.class.getClassLoader() + ")"
                    + " registrySize=" + AUTO_BRIDGE_REGISTRY.size()
                    + " lazyPopulatorRan=" + lazyPopulatorRan);
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

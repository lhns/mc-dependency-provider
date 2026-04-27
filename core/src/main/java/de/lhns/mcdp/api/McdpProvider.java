package de.lhns.mcdp.api;

import de.lhns.mcdp.core.ModClassLoader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
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

    /**
     * Register a mod's {@link ModClassLoader} so {@link #loadMixinImpl(Class)} can find it at
     * runtime. Called by platform adapters once per mod during boot.
     */
    public static void registerMod(String modId, ModClassLoader loader) {
        MOD_LOADERS_BY_ID.put(Objects.requireNonNull(modId), Objects.requireNonNull(loader));
        wireAutoMixinBridges(modId, loader);
    }

    /**
     * Read every {@code META-INF/mcdp-mixin-bridges/<mixinFqn>.txt} resource on the per-mod
     * classloader, instantiate the bridge impl through that loader, and assign it to the
     * mixin class's static {@code LOGIC_*} field. The mixin class itself is loaded by the
     * <em>game-layer</em> classloader (Mixin owns its identity); we look it up via
     * {@code Class.forName(fqn, true, gameLoader)}, where {@code gameLoader} is the parent of
     * the {@link ModClassLoader}.
     *
     * <p>Manifest schema (one block per bridge target, blank lines ignored):
     * <pre>
     * bridge=com.example.mod.mcdp_mixin_bridges.FooBridge
     * impl=com.example.mod.mcdp_mixin_bridges.FooBridgeImpl
     * field=LOGIC_Foo
     * </pre>
     */
    private static void wireAutoMixinBridges(String modId, ModClassLoader modLoader) {
        List<URL> txtFiles = new ArrayList<>();
        for (URL root : modLoader.getURLs()) {
            txtFiles.addAll(listBridgeManifests(root));
        }
        if (txtFiles.isEmpty()) return;
        ClassLoader gameLoader = modLoader.getParent();
        for (URL txt : txtFiles) {
            try {
                wireOne(txt, modLoader, gameLoader);
            } catch (Throwable t) {
                throw new IllegalStateException("mcdepprovider: failed to wire mixin bridge "
                        + txt + " for mod '" + modId + "': " + t.getMessage(), t);
            }
        }
    }

    private static List<URL> listBridgeManifests(URL jarOrDir) {
        List<URL> out = new ArrayList<>();
        try {
            String s = jarOrDir.toString();
            if (s.endsWith(".jar") || s.endsWith(".zip")) {
                try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(jarOrDir.openStream())) {
                    java.util.zip.ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        String n = e.getName();
                        if (n.startsWith("META-INF/mcdp-mixin-bridges/") && n.endsWith(".txt")) {
                            out.add(new URL("jar:" + jarOrDir + "!/" + n));
                        }
                    }
                }
            } else {
                java.io.File dir = new java.io.File(jarOrDir.toURI());
                java.io.File bridgeDir = new java.io.File(dir, "META-INF/mcdp-mixin-bridges");
                if (bridgeDir.isDirectory()) {
                    java.io.File[] children = bridgeDir.listFiles((d, name) -> name.endsWith(".txt"));
                    if (children != null) for (java.io.File f : children) out.add(f.toURI().toURL());
                }
            }
        } catch (Exception ignore) {
            // best effort; missing directories are normal
        }
        return out;
    }

    private static void wireOne(URL txtUrl, ModClassLoader modLoader, ClassLoader gameLoader) throws Exception {
        String urlStr = txtUrl.toString();
        int slash = urlStr.lastIndexOf('/');
        int dot = urlStr.lastIndexOf('.');
        String mixinFqn = urlStr.substring(slash + 1, dot);
        Class<?> mixinClass;
        try {
            mixinClass = Class.forName(mixinFqn, false, gameLoader);
        } catch (ClassNotFoundException e) {
            // Mixin classes in production are loaded by the launch classloader, which owns Mixin
            // ownership; in dev/Knot/TransformingClassLoader they reach the same identity. If the
            // class isn't visible to gameLoader, try the modLoader as a fallback (works in unit
            // tests where everything shares the test classloader).
            mixinClass = Class.forName(mixinFqn, false, modLoader);
        }
        try (InputStream in = txtUrl.openStream();
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
                    Class<?> implClass = Class.forName(impl, true, modLoader);
                    Object instance = implClass.getDeclaredConstructor().newInstance();
                    Field f = mixinClass.getDeclaredField(field);
                    f.setAccessible(true);
                    f.set(null, instance);
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
    }

    /** Package-private (exposed for tests) — clears just the impl cache. */
    static void clearImplCacheForTests() {
        MIXIN_IMPL_CACHE.clear();
    }
}

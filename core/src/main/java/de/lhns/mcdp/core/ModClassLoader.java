package de.lhns.mcdp.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

/**
 * Classloader for a single mod: URLs = mod jar + this mod's library loaders (chained). Parent =
 * the provider's own classloader (which transitively sees the JDK, Minecraft, NeoForge/Fabric, and
 * the provider's API).
 * <p>
 * Delegation:
 * <ul>
 *   <li><b>Parent-first</b> for the platform prefixes in {@link #PLATFORM_PREFIXES}.</li>
 *   <li><b>Parent-first</b> for anything under a prefix listed in {@code sharedPackages}
 *       (so mixin interfaces and mod API types stay the same {@code Class} across loaders).</li>
 *   <li><b>Child-first</b> otherwise — the mod's own bytecode and its Maven deps are loaded
 *       through this loader before any parent fallback. This is what keeps cats/circe/etc.
 *       isolated between mods and out of any JPMS module layer.</li>
 * </ul>
 * <p>
 * <b>Resources follow the same policy as classes.</b> {@link #getResource},
 * {@link #getResources} and {@link #getResourceAsStream} map the resource name back to a package
 * name ({@code '/'} → {@code '.'}) and apply the rules above, so a mod's own
 * {@code reference.conf}, {@code library.properties}, {@code logback.xml} or
 * {@code META-INF/services/*} entry resolves out of the mod's jars instead of the game layer's —
 * {@code ServiceLoader.load(X.class, modLoader)} would otherwise silently pick up the game's
 * providers. In the child-first case {@link #getResources} returns this loader's entries before
 * the parent's; in the parent-first case, the parent's first.
 * <p>
 * See ADR-0001 and ADR-0002.
 */
public final class ModClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    /**
     * Hard-coded platform namespaces that must resolve through the provider's parent loader so
     * mods see the same {@code Class} as Minecraft/NeoForge/Fabric themselves use. Kept
     * conservative — expand on concrete need.
     */
    static final List<String> PLATFORM_PREFIXES = List.of(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "org.w3c.",
            "org.xml.",
            "net.minecraft.",
            "net.neoforged.",
            "net.fabricmc.",
            "com.mojang.",
            "cpw.mods.",
            "org.slf4j.",
            "org.apache.logging.log4j.",
            "de.lhns.mcdp.api.",
            "de.lhns.mcdp.core."
    );

    private final List<String> sharedPackages;
    private final String modId;

    public ModClassLoader(String modId, URL[] urls, ClassLoader parent, List<String> sharedPackages) {
        super("mcdepprovider:" + Objects.requireNonNull(modId, "modId"), urls, parent);
        this.modId = modId;
        this.sharedPackages = normalizePrefixes(sharedPackages);
    }

    /**
     * Force every shared-package entry to end in {@code '.'}, because matching is a raw
     * {@code startsWith}: an entry written as {@code com.example.api} would otherwise also share
     * {@code com.example.apiInternal} and {@code com.example.apix} with the parent — exactly the
     * isolation leak this loader exists to prevent. {@link #PLATFORM_PREFIXES} and the
     * plugin-generated bridge package already carry the dot; this makes it a guarantee rather
     * than a convention. Blank entries are dropped (a trailing {@code "."} alone would share
     * the default package, i.e. everything).
     */
    private static List<String> normalizePrefixes(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (String p : raw) {
            if (p == null || p.isBlank()) continue;
            out.add(p.endsWith(".") ? p : p + ".");
        }
        return List.copyOf(out);
    }

    public String modId() {
        return modId;
    }

    public List<String> sharedPackages() {
        return sharedPackages;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (isParentFirst(name)) {
                    try {
                        c = getParent().loadClass(name);
                    } catch (ClassNotFoundException parentMiss) {
                        try {
                            c = findClass(name);
                        } catch (ClassNotFoundException childMiss) {
                            throw decorateMissing(name, childMiss);
                        }
                    }
                } else {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException childMiss) {
                        try {
                            c = getParent().loadClass(name);
                        } catch (ClassNotFoundException parentMiss) {
                            throw decorateMissing(name, parentMiss);
                        }
                    }
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    public URL getResource(String name) {
        if (isParentFirstResource(name)) {
            URL fromParent = getParent().getResource(name);
            return fromParent != null ? fromParent : findResource(name);
        }
        URL fromChild = findResource(name);
        return fromChild != null ? fromChild : getParent().getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        Enumeration<URL> child = findResources(name);
        Enumeration<URL> parent = getParent().getResources(name);
        return isParentFirstResource(name)
                ? concatResources(parent, child)
                : concatResources(child, parent);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        URL url = getResource(name);
        if (url == null) return null;
        try {
            // useCaches(false): the JVM-wide JarFile cache would otherwise keep a handle on the
            // mod's jar that neither we nor close() can release — on Windows that pins the file.
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            return connection.getInputStream();
        } catch (IOException e) {
            // Same contract as ClassLoader#getResourceAsStream: a resource we can name but not
            // open is reported as absent.
            return null;
        }
    }

    /**
     * Resource names are package paths with {@code '/'} separators, so we can reuse the class
     * policy verbatim by translating back. {@code META-INF/services/…} and other non-package
     * resources never match a prefix and therefore stay child-first, which is what makes
     * {@code ServiceLoader} see the mod's own providers.
     */
    private boolean isParentFirstResource(String name) {
        String asPackage = name.replace('/', '.');
        return isParentFirst(asPackage);
    }

    /** Lazily concatenates two resource enumerations, first exhausted first. */
    static Enumeration<URL> concatResources(Enumeration<URL> first, Enumeration<URL> second) {
        return new Enumeration<>() {
            @Override
            public boolean hasMoreElements() {
                return first.hasMoreElements() || second.hasMoreElements();
            }

            @Override
            public URL nextElement() {
                return first.hasMoreElements() ? first.nextElement() : second.nextElement();
            }
        };
    }

    /**
     * If the missing class looks mod-private (no platform prefix, not in {@code sharedPackages})
     * or sits under {@code scala.}/{@code kotlin.}, rethrow with a tailored message that points
     * the user at the Mixin bridge documentation. The original exception is preserved as the
     * cause. For genuinely platform-domain misses we surface the bare {@code ClassNotFoundException}
     * so JVM/log output matches a stock {@code URLClassLoader}.
     */
    private ClassNotFoundException decorateMissing(String name, ClassNotFoundException cause) {
        if (!looksModPrivate(name)) return cause;
        String msg = "mcdepprovider: " + name + " isn't visible to this classloader (mod '"
                + modId + "'). If this came from a Mixin, automatic bridge codegen handles "
                + "cross-classloader calls — make sure you haven't disabled it via "
                + "`bridges { enabled.set(false) }`. See docs/bridges.md "
                + "(ADR-0008/0018) for the hand-written-bridge path if you need explicit control.";
        ClassNotFoundException tailored = new ClassNotFoundException(msg, cause);
        tailored.setStackTrace(cause.getStackTrace());
        return tailored;
    }

    private boolean looksModPrivate(String name) {
        if (name.startsWith("scala.") || name.startsWith("kotlin.")) return true;
        for (String prefix : PLATFORM_PREFIXES) {
            if (name.startsWith(prefix)) return false;
        }
        for (String prefix : sharedPackages) {
            if (name.startsWith(prefix)) return false;
        }
        return true;
    }

    private boolean isParentFirst(String name) {
        for (String prefix : PLATFORM_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        for (String prefix : sharedPackages) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}

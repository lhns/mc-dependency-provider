package de.lhns.mcdp.core;

import java.net.URL;
import java.net.URLClassLoader;
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
        this.sharedPackages = List.copyOf(sharedPackages);
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

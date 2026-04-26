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
                        c = findClass(name);
                    }
                } else {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException childMiss) {
                        c = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
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

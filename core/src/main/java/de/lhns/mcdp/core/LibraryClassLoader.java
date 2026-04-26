package de.lhns.mcdp.core;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * URLClassLoader for a SHA-coalesced set of library jars. Child-first delegation for everything
 * except {@link ModClassLoader#PLATFORM_PREFIXES}, so libraries (cats, circe, scala-library, ...)
 * resolve out of this loader's URLs even when a parent loader (Knot in Fabric dev-mode, or the
 * game module layer on NeoForge) happens to carry a copy on its classpath.
 */
final class LibraryClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    LibraryClassLoader(String name, URL[] urls, ClassLoader parent) {
        super(name, urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (isParentFirst(name)) {
                    try {
                        c = getParent().loadClass(name);
                    } catch (ClassNotFoundException miss) {
                        c = findClass(name);
                    }
                } else {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException miss) {
                        c = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    private static boolean isParentFirst(String name) {
        for (String prefix : ModClassLoader.PLATFORM_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}

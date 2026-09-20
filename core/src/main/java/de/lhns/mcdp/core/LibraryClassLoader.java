package de.lhns.mcdp.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.Enumeration;

/**
 * URLClassLoader for a SHA-coalesced set of library jars. Child-first delegation for everything
 * except {@link ModClassLoader#PLATFORM_PREFIXES}, so libraries (cats, circe, scala-library, ...)
 * resolve out of this loader's URLs even when a parent loader (Knot in Fabric dev-mode, or the
 * game module layer on NeoForge) happens to carry a copy on its classpath.
 * <p>
 * Resources follow the same policy — a library's {@code reference.conf},
 * {@code library.properties} or {@code META-INF/services/*} must come from the jar we resolved,
 * not from whatever copy the game layer carries.
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
                ? ModClassLoader.concatResources(parent, child)
                : ModClassLoader.concatResources(child, parent);
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
            return null;
        }
    }

    /** Resource names are package paths with {@code '/'} separators; reuse the class policy. */
    private static boolean isParentFirstResource(String name) {
        return isParentFirst(name.replace('/', '.'));
    }

    private static boolean isParentFirst(String name) {
        for (String prefix : ModClassLoader.PLATFORM_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}

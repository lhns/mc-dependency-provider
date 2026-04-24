package io.github.mclibprovider.core;

import io.github.mclibprovider.deps.Manifest;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds {@link ModClassLoader}s and coalesces duplicate library loaders by SHA-256. See ADR-0006.
 * <p>
 * Thread-safe; platform adapters can register mods from multiple threads during boot.
 */
public final class LoaderCoordinator {

    private final ClassLoader parent;
    private final Map<String, URLClassLoader> libraryLoaders = new ConcurrentHashMap<>();
    private final Map<String, ModClassLoader> modLoaders = new ConcurrentHashMap<>();

    public LoaderCoordinator(ClassLoader parent) {
        this.parent = parent;
    }

    /**
     * Build a {@link ModClassLoader} for a mod, using a URL list that combines the mod jar and
     * its manifest-declared library jars. Library jars are reached through SHA-keyed singleton
     * {@link URLClassLoader}s so two mods pinning the same SHA share classes.
     * <p>
     * The parent-of-parents is {@link #parent}. The mod loader chains:
     * {@code ModClassLoader → library-of-sha1 → library-of-sha2 → ... → parent}.
     */
    public ModClassLoader register(String modId, Manifest manifest, Path modJar, List<Path> libraryJars) {
        return register(modId, manifest, List.of(modJar), libraryJars);
    }

    /**
     * Variant of {@link #register(String, Manifest, Path, List)} that accepts multiple mod-owned
     * roots (useful in dev-mode where Loom/ModDevGradle expose the mod as separate
     * {@code build/classes/.../main} and {@code build/resources/main} directories rather than a
     * single jar).
     */
    public ModClassLoader register(String modId, Manifest manifest, List<Path> modPaths, List<Path> libraryJars) {
        return register(modId, manifest, modPaths, libraryJars, parent);
    }

    /**
     * Build a shared {@link LibraryClassLoader} over a given set of jars, keyed by the sorted SHA
     * set. Intended for stdlib promotion (ADR-0010 / {@link StdlibPromotion}): adapters call this
     * once with the selected promoted libs and hand the returned loader as the
     * {@code parentLibLoader} to subsequent {@link #register} calls, so promoted classes resolve
     * from one shared loader regardless of which mod triggers the lookup.
     */
    public URLClassLoader buildSharedLibraryLoader(List<Path> jars, List<String> sha256s) {
        if (jars.size() != sha256s.size()) {
            throw new IllegalArgumentException("jar/sha count mismatch: jars=" + jars.size() + ", shas=" + sha256s.size());
        }
        if (jars.isEmpty()) return null;
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            try {
                urls[i] = jars.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("bad jar URL: " + jars.get(i), e);
            }
        }
        List<String> sortedShas = new ArrayList<>(sha256s);
        Collections.sort(sortedShas);
        String key = "promoted:" + String.join(",", sortedShas);
        final URL[] urlsFinal = urls;
        return libraryLoaders.computeIfAbsent(key, k ->
                new LibraryClassLoader("mc-lib-provider-promoted:" + k.substring(10, Math.min(26, k.length())),
                        urlsFinal, parent));
    }

    /**
     * Canonical {@code register} overload. Both platform adapters (Fabric {@code McLibPreLaunch},
     * NeoForge {@code McLibLanguageLoader}) call this form directly so the stdlib-promoted parent
     * loader can be spliced in above the per-mod libs. The 3-arg and 4-arg overloads delegate here
     * with {@link #parent} as the library-loader parent and exist for tests and external callers.
     */
    public ModClassLoader register(String modId, Manifest manifest, List<Path> modPaths,
                                   List<Path> libraryJars, ClassLoader parentLibLoader) {
        if (manifest.libraries().size() != libraryJars.size()) {
            throw new IllegalArgumentException(
                    "library-count mismatch: manifest=" + manifest.libraries().size() + ", paths=" + libraryJars.size());
        }

        // ADR-0006 originally proposed chaining one URLClassLoader per library SHA. That
        // only works when the manifest is topologically sorted — otherwise a library
        // class referencing a class defined by a "sibling" library fails to link at
        // defineClass time (each URLClassLoader only sees its own jar + ancestors).
        // To keep correctness simple while preserving per-mod isolation, bundle all
        // library URLs into one aggregate URLClassLoader keyed by the sorted SHA set
        // so two mods declaring an identical library closure still share a loader.
        URL[] libUrls = new URL[libraryJars.size()];
        List<String> shas = new java.util.ArrayList<>(manifest.libraries().size());
        for (int i = 0; i < manifest.libraries().size(); i++) {
            String sha = manifest.libraries().get(i).sha256();
            Path jar = libraryJars.get(i);
            try {
                libUrls[i] = jar.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("bad jar URL: " + jar, e);
            }
            shas.add(sha);
        }
        java.util.Collections.sort(shas);
        String aggregateKey = String.join(",", shas);

        final URL[] libUrlsFinal = libUrls;
        // Cache key includes the override parent so two mods with the same reduced-lib SHA set
        // but different promoted-stdlib parents don't collide into a single loader.
        String cacheKey = aggregateKey + "|" + System.identityHashCode(parentLibLoader);
        URLClassLoader libsLoader = libraryLoaders.computeIfAbsent(cacheKey, k ->
                new LibraryClassLoader("mc-lib-provider-libs:" + aggregateKey.substring(0, Math.min(16, aggregateKey.length())),
                        libUrlsFinal, parentLibLoader));

        URL[] modUrls = new URL[modPaths.size()];
        for (int i = 0; i < modPaths.size(); i++) {
            Path p = modPaths.get(i);
            try {
                modUrls[i] = p.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("bad mod jar URL: " + p, e);
            }
        }

        ModClassLoader modCl = new ModClassLoader(modId, modUrls, libsLoader, manifest.sharedPackages());
        modLoaders.put(modId, modCl);
        return modCl;
    }

    public ModClassLoader loaderFor(String modId) {
        return modLoaders.get(modId);
    }

    public Map<String, ModClassLoader> allLoaders() {
        return Collections.unmodifiableMap(modLoaders);
    }

    public List<URLClassLoader> libraryLoaders() {
        return new ArrayList<>(libraryLoaders.values());
    }
}

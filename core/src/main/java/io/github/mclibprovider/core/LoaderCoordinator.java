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
        if (manifest.libraries().size() != libraryJars.size()) {
            throw new IllegalArgumentException(
                    "library-count mismatch: manifest=" + manifest.libraries().size() + ", paths=" + libraryJars.size());
        }

        ClassLoader chain = parent;
        for (int i = 0; i < manifest.libraries().size(); i++) {
            String sha = manifest.libraries().get(i).sha256();
            Path jar = libraryJars.get(i);
            final ClassLoader chainSnapshot = chain;
            URLClassLoader lib = libraryLoaders.computeIfAbsent(sha, s -> {
                try {
                    return new URLClassLoader(
                            "mc-lib-provider-lib:" + s,
                            new URL[]{jar.toUri().toURL()},
                            chainSnapshot);
                } catch (MalformedURLException e) {
                    throw new IllegalStateException("bad jar URL: " + jar, e);
                }
            });
            chain = lib;
        }

        URL modUrl;
        try {
            modUrl = modJar.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("bad mod jar URL: " + modJar, e);
        }

        ModClassLoader modCl = new ModClassLoader(modId, new URL[]{modUrl}, chain, manifest.sharedPackages());
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

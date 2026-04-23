package io.github.mclibprovider.gradle;

import io.github.mclibprovider.deps.LibraryCache;
import io.github.mclibprovider.deps.Manifest;
import io.github.mclibprovider.deps.ManifestIo;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Populates the shared library cache ({@code ~/.cache/mc-lib-provider/libs/<sha>.jar}) from the
 * project's locally-resolved jars, hard-linking when the filesystem supports it. No network.
 * <p>
 * Runs before dev run-tasks so the provider's runtime loading path is exercised end-to-end in dev
 * — the provider sees cache-hits for every SHA and never calls the network. See ADR-0007.
 */
public abstract class PrepareDevCacheTask extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getManifestFile();

    @InputFiles
    public abstract ConfigurableFileCollection getArtifactFiles();

    /** Optional override of the cache root; defaults to {@link LibraryCache#defaultCache()}. */
    @org.gradle.api.tasks.Input
    @Optional
    public abstract Property<String> getCacheRoot();

    @TaskAction
    public void prewarm() throws IOException {
        Manifest manifest = ManifestIo.read(getManifestFile().get().getAsFile().toPath());

        // Index local files by basename (artifact-version.jar). We match by file name since the
        // manifest's URL is a canonical Maven path; what matters is the bytes on disk match the SHA.
        Map<String, Path> localByName = new HashMap<>();
        for (var f : getArtifactFiles().getFiles()) {
            localByName.put(f.getName(), f.toPath());
        }

        LibraryCache cache = getCacheRoot().isPresent()
                ? new LibraryCache(Path.of(getCacheRoot().get()))
                : LibraryCache.defaultCache();

        int linked = 0;
        int skipped = 0;
        for (Manifest.Library lib : manifest.libraries()) {
            if (cache.contains(lib.sha256())) {
                skipped++;
                continue;
            }
            String filename = extractFilename(lib.url());
            Path source = localByName.get(filename);
            if (source == null) {
                getLogger().warn("mc-lib-provider: no local artifact matching {} — first boot will download", filename);
                continue;
            }
            cache.linkOrCopy(source, lib.sha256());
            linked++;
        }
        getLogger().lifecycle("mc-lib-provider: dev cache pre-warm — linked {}, already-present {}", linked, skipped);
    }

    private static String extractFilename(String url) {
        int slash = url.lastIndexOf('/');
        return slash < 0 ? url : url.substring(slash + 1);
    }
}

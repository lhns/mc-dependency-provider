package io.github.mclibprovider.cli;

import io.github.mclibprovider.deps.LibraryCache;
import io.github.mclibprovider.deps.Manifest;
import io.github.mclibprovider.deps.ManifestConsumer;
import io.github.mclibprovider.deps.ManifestIo;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Offline-prefetch CLI for modpack authors (task #33 / open-question #2).
 *
 * Given a set of mod-jar paths or directories, reads each mod's
 * {@code META-INF/mclibprovider.toml} and pre-populates the shared library cache so
 * the first in-game boot on an offline machine is a pure cache-hit.
 *
 * Usage:
 *   mc-lib-provider-prefetch [--cache DIR] [--dry-run] PATH [PATH...]
 *
 * Each PATH is either:
 *   - a mod jar (processed directly), or
 *   - a directory (all {@code *.jar} files processed).
 *
 * Exit codes: 0 success; 1 argument error; 2 manifest/download failure (propagates
 * {@link ManifestConsumer}'s SHA-256 mismatch diagnostics verbatim).
 */
public final class Prefetch {

    private static final String MANIFEST_ENTRY = "META-INF/mclibprovider.toml";

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Path cacheOverride = null;
        boolean dryRun = false;
        List<Path> inputs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--cache" -> {
                    if (i + 1 >= args.length) {
                        err.println("--cache requires a path argument");
                        return 1;
                    }
                    cacheOverride = Path.of(args[++i]);
                }
                case "--dry-run" -> dryRun = true;
                case "-h", "--help" -> {
                    out.println("usage: mc-lib-provider-prefetch [--cache DIR] [--dry-run] PATH [PATH...]");
                    return 0;
                }
                default -> inputs.add(Path.of(a));
            }
        }

        if (inputs.isEmpty()) {
            err.println("error: at least one mod jar or directory is required");
            return 1;
        }

        List<Path> jars = new ArrayList<>();
        for (Path p : inputs) {
            if (!Files.exists(p)) {
                err.println("error: no such path: " + p);
                return 1;
            }
            if (Files.isDirectory(p)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.jar")) {
                    for (Path jar : ds) jars.add(jar);
                } catch (IOException e) {
                    err.println("error: listing " + p + ": " + e.getMessage());
                    return 1;
                }
            } else {
                jars.add(p);
            }
        }

        LibraryCache cache = cacheOverride != null
                ? new LibraryCache(cacheOverride)
                : LibraryCache.defaultCache();
        ManifestConsumer consumer = new ManifestConsumer(cache);

        int totalLibs = 0;
        int processedJars = 0;
        int skippedJars = 0;
        for (Path jar : jars) {
            Manifest manifest;
            try {
                manifest = readManifestFromJar(jar);
            } catch (IOException e) {
                err.println("error: reading manifest from " + jar + ": " + e.getMessage());
                return 2;
            }
            if (manifest == null) {
                out.println("skip: " + jar.getFileName() + " (no " + MANIFEST_ENTRY + ")");
                skippedJars++;
                continue;
            }
            out.println("mod:  " + jar.getFileName() + " (" + manifest.libraries().size() + " libs, lang=" + manifest.lang() + ")");
            totalLibs += manifest.libraries().size();
            if (dryRun) continue;
            try {
                consumer.resolveAll(manifest);
            } catch (IOException e) {
                err.println("error: prefetching for " + jar + ": " + e.getMessage());
                return 2;
            }
            processedJars++;
        }

        out.println("done: " + processedJars + " mod(s) prefetched, "
                + skippedJars + " skipped, " + totalLibs + " library reference(s)"
                + (dryRun ? " (dry-run)" : "") + " -> cache at " + cache.root());
        return 0;
    }

    static Manifest readManifestFromJar(Path jar) throws IOException {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry entry = zf.getEntry(MANIFEST_ENTRY);
            if (entry == null) return null;
            try (InputStream in = zf.getInputStream(entry)) {
                return ManifestIo.read(in);
            }
        }
    }

    private Prefetch() {}
}

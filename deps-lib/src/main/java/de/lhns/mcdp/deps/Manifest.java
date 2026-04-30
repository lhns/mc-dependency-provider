package de.lhns.mcdp.deps;

import java.util.List;
import java.util.Objects;

/**
 * The parsed form of a mod's {@code META-INF/mcdepprovider.toml}.
 * <p>
 * Flat, pre-resolved, SHA-pinned. See ADR-0003.
 *
 * <p>{@link #devRoots()} is populated only when the manifest was emitted by a Gradle dev build
 * (the gradle plugin writes the absolute paths of the source-set output dirs that hold the mod's
 * compiled classes + bridge codegen output). Production jars don't ship with this field — when
 * absent, the platform adapter uses the loader's reported {@code rootPaths} verbatim. This
 * replaces the older filesystem-walk heuristic that hardcoded Gradle's {@code build/}
 * directory layout.
 */
public record Manifest(
        String lang,
        List<String> sharedPackages,
        List<Library> libraries,
        List<String> devRoots) {

    public Manifest {
        Objects.requireNonNull(lang, "lang");
        sharedPackages = List.copyOf(Objects.requireNonNullElse(sharedPackages, List.of()));
        libraries = List.copyOf(Objects.requireNonNullElse(libraries, List.of()));
        devRoots = List.copyOf(Objects.requireNonNullElse(devRoots, List.of()));
    }

    /** Convenience for tests + back-compat readers that don't supply dev roots. */
    public Manifest(String lang, List<String> sharedPackages, List<Library> libraries) {
        this(lang, sharedPackages, libraries, List.of());
    }

    public record Library(String coords, String url, String sha256) {
        public Library {
            Objects.requireNonNull(coords, "coords");
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(sha256, "sha256");
            if (sha256.length() != 64) {
                throw new IllegalArgumentException("sha256 must be 64 hex chars, got: " + sha256);
            }
        }
    }
}

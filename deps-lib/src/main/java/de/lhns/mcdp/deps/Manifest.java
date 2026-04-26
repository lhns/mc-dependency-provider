package de.lhns.mcdp.deps;

import java.util.List;
import java.util.Objects;

/**
 * The parsed form of a mod's {@code META-INF/mclibprovider.toml}.
 * <p>
 * Flat, pre-resolved, SHA-pinned. See ADR-0003.
 */
public record Manifest(
        String lang,
        List<String> sharedPackages,
        List<Library> libraries) {

    public Manifest {
        Objects.requireNonNull(lang, "lang");
        sharedPackages = List.copyOf(Objects.requireNonNullElse(sharedPackages, List.of()));
        libraries = List.copyOf(Objects.requireNonNullElse(libraries, List.of()));
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

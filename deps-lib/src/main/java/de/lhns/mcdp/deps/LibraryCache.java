package de.lhns.mcdp.deps;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;

/**
 * On-disk store of library jars keyed by their SHA-256. Shared across NeoForge and Fabric; shared
 * between dev-mode pre-warm (Gradle plugin) and production downloads (runtime).
 * <p>
 * Layout: {@code <root>/libs/<sha256>.jar}.
 */
public final class LibraryCache {

    private final Path root;

    public LibraryCache(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** Default cache location. Respects {@code MCDEPPROVIDER_CACHE} env var; falls back to OS conventions. */
    public static LibraryCache defaultCache() {
        String override = System.getenv("MCDEPPROVIDER_CACHE");
        if (override == null || override.isBlank()) {
            // Compatibility shim: MC_LIB_PROVIDER_CACHE is the pre-rename name, still honoured so
            // existing setups don't silently fall back to the OS-default location.
            override = System.getenv("MC_LIB_PROVIDER_CACHE");
        }
        if (override != null && !override.isBlank()) {
            return new LibraryCache(Path.of(override));
        }
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path root;
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            root = (local != null ? Path.of(local) : Path.of(home, "AppData", "Local")).resolve("mcdepprovider");
        } else if (os.contains("mac")) {
            root = Path.of(home, "Library", "Caches", "mcdepprovider");
        } else {
            String xdg = System.getenv("XDG_CACHE_HOME");
            root = (xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".cache")).resolve("mcdepprovider");
        }
        return new LibraryCache(root);
    }

    public Path root() {
        return root;
    }

    public Path libsDir() {
        return root.resolve("libs");
    }

    public Path pathFor(String sha256) {
        if (sha256.length() != 64) {
            throw new IllegalArgumentException("sha256 must be 64 hex chars, got: " + sha256);
        }
        return libsDir().resolve(sha256 + ".jar");
    }

    public boolean contains(String sha256) {
        return Files.isRegularFile(pathFor(sha256));
    }

    /** Store bytes under the given SHA. Caller is responsible for having verified the SHA. Atomic via tmp-then-rename. */
    public Path store(String sha256, byte[] bytes) throws IOException {
        Files.createDirectories(libsDir());
        Path target = pathFor(sha256);
        if (Files.exists(target)) {
            return target;
        }
        Path tmp = Files.createTempFile(libsDir(), sha256 + "-", ".jar.tmp");
        try {
            Files.write(tmp, bytes);
            atomicMove(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    /** Hard-link the given source jar into the cache at {@code sha256}. Falls back to a copy if the FS rejects links. */
    public Path linkOrCopy(Path source, String sha256) throws IOException {
        Files.createDirectories(libsDir());
        Path target = pathFor(sha256);
        if (Files.exists(target)) {
            return target;
        }
        Path tmp = Files.createTempFile(libsDir(), sha256 + "-", ".jar.tmp");
        Files.deleteIfExists(tmp);
        try {
            try {
                Files.createLink(tmp, source);
            } catch (UnsupportedOperationException | IOException linkFailed) {
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            atomicMove(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    /**
     * Move {@code tmp} onto {@code target}, tolerating another writer doing the same thing.
     * <p>
     * Two mods depending on one library download it concurrently, so this is the normal case, not
     * an edge. The previous version caught {@link FileAlreadyExistsException} on both moves and
     * neither catch was reachable: {@code ATOMIC_MOVE} is {@code MoveFileEx(REPLACE_EXISTING)} on
     * Windows and {@code rename(2)} on POSIX, and {@code REPLACE_EXISTING} cannot raise it by
     * definition. What actually happens on Windows is {@code AccessDeniedException} or
     * {@code NoSuchFileException} out of the fallback move, which escaped as a failed dependency
     * download in roughly one concurrent store in seven.
     * <p>
     * Losing the race is not a failure here: the cache is content-addressed and {@code target} is
     * named for the SHA-256 the caller has already verified, so a target that exists now holds the
     * bytes we were about to write.
     */
    private static void atomicMove(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException atomicFailed) {
            // Cross-device, or the platform has no atomic move — fall through to the plain one.
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException replaceFailed) {
            // isRegularFile, not exists: a directory sitting at a SHA-named path is a corrupt
            // cache, not a lost race, and must stay loud.
            if (!Files.isRegularFile(target)) throw replaceFailed;
        }
    }
}

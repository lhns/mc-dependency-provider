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

    /** Default cache location. Respects {@code MC_LIB_PROVIDER_CACHE} env var; falls back to OS conventions. */
    public static LibraryCache defaultCache() {
        String override = System.getenv("MC_LIB_PROVIDER_CACHE");
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

    private static void atomicMove(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException alreadyThere) {
            // Another writer won — fine, both bytes are SHA-pinned to the same content.
        } catch (IOException atomicFailed) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (FileAlreadyExistsException ignored) {
                // Same race as above.
            }
        }
    }
}

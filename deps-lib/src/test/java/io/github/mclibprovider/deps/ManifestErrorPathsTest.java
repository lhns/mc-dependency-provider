package io.github.mclibprovider.deps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the failure modes a mod user is likely to see in the wild:
 * missing manifest, malformed TOML, partial library entries, network unreachable. The goal is a
 * clear diagnostic message, never a cryptic {@link NullPointerException}.
 */
class ManifestErrorPathsTest {

    private static final String VALID_SHA = "a".repeat(64);

    @Test
    void missingManifestFileSurfacesIoException(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.toml");
        IOException ex = assertThrows(IOException.class, () -> ManifestIo.read(missing));
        String msg = String.valueOf(ex.getMessage());
        assertTrue(msg.contains("does-not-exist.toml") || msg.toLowerCase().contains("no such"),
                "diagnostic should mention the path; was: " + msg);
    }

    @Test
    void malformedTomlMentionsParseError() {
        String broken = "lang = \"scala\"\n[[libraries\ncoords = \"a:b:1\"\n";
        IOException ex = assertThrows(IOException.class,
                () -> ManifestIo.read(new ByteArrayInputStream(broken.getBytes(StandardCharsets.UTF_8))));
        assertTrue(ex.getMessage().startsWith("Invalid manifest TOML"),
                "expected 'Invalid manifest TOML' prefix; was: " + ex.getMessage());
    }

    @Test
    void libraryEntryMissingCoordsIsRejected() {
        String toml = """
                lang = "java"

                [[libraries]]
                url    = "https://example.com/lib.jar"
                sha256 = "%s"
                """.formatted(VALID_SHA);

        IOException ex = assertThrows(IOException.class,
                () -> ManifestIo.read(new ByteArrayInputStream(toml.getBytes(StandardCharsets.UTF_8))));
        assertTrue(ex.getMessage().contains("coords"),
                "expected missing-coords diagnostic; was: " + ex.getMessage());
    }

    @Test
    void libraryEntryMissingShaIsRejected() {
        String toml = """
                lang = "java"

                [[libraries]]
                coords = "a:b:1"
                url    = "https://example.com/lib.jar"
                """;

        IOException ex = assertThrows(IOException.class,
                () -> ManifestIo.read(new ByteArrayInputStream(toml.getBytes(StandardCharsets.UTF_8))));
        assertTrue(ex.getMessage().contains("sha256"),
                "expected missing-sha256 diagnostic; was: " + ex.getMessage());
    }

    @Test
    void libraryEntryMissingUrlIsRejected() {
        String toml = """
                lang = "java"

                [[libraries]]
                coords = "a:b:1"
                sha256 = "%s"
                """.formatted(VALID_SHA);

        IOException ex = assertThrows(IOException.class,
                () -> ManifestIo.read(new ByteArrayInputStream(toml.getBytes(StandardCharsets.UTF_8))));
        assertTrue(ex.getMessage().contains("url"),
                "expected missing-url diagnostic; was: " + ex.getMessage());
    }

    @Test
    void networkUnreachableSurfacesClearly(@TempDir Path tmp) {
        // RFC 2606 reserves the .invalid TLD for exactly this: guaranteed not to resolve.
        String dead = "http://mc-lib-provider-test.invalid/absent.jar";
        Manifest manifest = new Manifest("java", List.of(), List.of(
                new Manifest.Library("a:b:1", dead, VALID_SHA)));
        ManifestConsumer consumer = new ManifestConsumer(new LibraryCache(tmp));

        IOException ex = assertThrows(IOException.class, () -> consumer.resolveAll(manifest));
        String msg = String.valueOf(ex.getMessage());
        assertTrue(msg.contains(dead),
                "expected diagnostic to mention the URL; was: " + msg);
        assertTrue(msg.toLowerCase().contains("failed to download"),
                "expected 'Failed to download' prefix; was: " + msg);
    }

    @Test
    void storeFailsLoudlyWhenCacheDirCannotBeCreated(@TempDir Path tmp) throws IOException {
        // Blocks cache creation by making "libs" a regular file instead of a directory.
        Path fakeRoot = tmp.resolve("cache");
        Files.createDirectories(fakeRoot);
        Files.writeString(fakeRoot.resolve("libs"), "not-a-directory");

        LibraryCache cache = new LibraryCache(fakeRoot);
        IOException ex = assertThrows(IOException.class, () -> cache.store(VALID_SHA, new byte[] {1, 2, 3}));
        // NotDirectory / FileAlreadyExists / similar IOException — the key is we get one, not an NPE.
        assertTrue(ex.getMessage() != null && !ex.getMessage().isBlank(),
                "IOException should carry a diagnostic; got: " + ex);
    }
}

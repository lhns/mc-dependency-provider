package de.lhns.mcdp.deps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LibraryCacheTest {

    private static final String SHA = "0123456789abcdef".repeat(4);

    @Test
    void storesAndRetrievesBytes(@TempDir Path tmp) throws IOException {
        LibraryCache cache = new LibraryCache(tmp);
        assertFalse(cache.contains(SHA));
        byte[] payload = "hello".getBytes();
        Path stored = cache.store(SHA, payload);
        assertTrue(cache.contains(SHA));
        assertEquals(stored, cache.pathFor(SHA));
        assertArrayEquals(payload, Files.readAllBytes(stored));
    }

    @Test
    void secondStoreIsNoop(@TempDir Path tmp) throws IOException {
        LibraryCache cache = new LibraryCache(tmp);
        cache.store(SHA, "first".getBytes());
        cache.store(SHA, "second-would-collide".getBytes());
        // The file written the first time wins; no exception.
        assertEquals("first", new String(Files.readAllBytes(cache.pathFor(SHA))));
    }

    @Test
    void linkOrCopyBringsFileIntoCache(@TempDir Path tmp) throws IOException {
        LibraryCache cache = new LibraryCache(tmp.resolve("cache"));
        Path source = tmp.resolve("source.jar");
        Files.writeString(source, "payload");
        Path linked = cache.linkOrCopy(source, SHA);
        assertTrue(Files.isRegularFile(linked));
        assertEquals("payload", Files.readString(linked));
    }

    @Test
    void rejectsInvalidSha(@TempDir Path tmp) {
        LibraryCache cache = new LibraryCache(tmp);
        assertThrows(IllegalArgumentException.class, () -> cache.pathFor("too-short"));
    }
}

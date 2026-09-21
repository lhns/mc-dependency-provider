package de.lhns.mcdp.gradle;

import de.lhns.mcdp.deps.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dev-cache pre-warm matches local artifacts by basename, which says nothing about their
 * bytes. Installing one under the manifest's SHA anyway would poison a content-addressed cache
 * permanently — every later {@code contains(sha)} reports a verified hit.
 */
class PrepareDevCacheTaskTest {

    @Test
    void acceptsAJarWhoseBytesHashToTheExpectedSha(@TempDir Path tmp) throws IOException {
        byte[] bytes = "the real artifact".getBytes();
        Path jar = Files.write(tmp.resolve("lib-1.0.jar"), bytes);
        assertTrue(PrepareDevCacheTask.matchesSha(jar, Sha256.hex(bytes)));
    }

    @Test
    void rejectsAJarWithTheRightNameAndTheWrongBytes(@TempDir Path tmp) throws IOException {
        // Same basename the manifest URL ends in, different content: a stale local build, or a
        // classifier collision. Basename matching cannot tell these apart; the hash can.
        Path jar = Files.write(tmp.resolve("lib-1.0.jar"), "a stale local build".getBytes());
        assertFalse(PrepareDevCacheTask.matchesSha(jar, Sha256.hex("the real artifact".getBytes())));
    }

    @Test
    void shaComparisonIsCaseInsensitive(@TempDir Path tmp) throws IOException {
        // Manifests in the wild carry upper-case digests; ManifestConsumer already tolerates them.
        byte[] bytes = "content".getBytes();
        Path jar = Files.write(tmp.resolve("lib-1.0.jar"), bytes);
        assertTrue(PrepareDevCacheTask.matchesSha(jar, Sha256.hex(bytes).toUpperCase()));
    }
}

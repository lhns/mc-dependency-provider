package de.lhns.mcdp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevRootSafetyNetTest {

    @Test
    void appendsBridgeDirWhenReachableFromBuildClasses(@TempDir Path tmp) throws IOException {
        Path build = Files.createDirectories(tmp.resolve("build"));
        Path javaMain = Files.createDirectories(build.resolve("classes/java/main"));
        Path bridge = Files.createDirectories(build.resolve("mcdp-bridges/classes"));

        List<Path> result = DevRootSafetyNet.appendBridgeClassesIfPresent(List.of(javaMain));

        assertTrue(result.contains(bridge), "bridge dir should be appended");
        assertTrue(result.contains(javaMain), "original entry should be preserved");
    }

    @Test
    void appendsBridgeDirFromResourcesMain(@TempDir Path tmp) throws IOException {
        Path build = Files.createDirectories(tmp.resolve("build"));
        Path resources = Files.createDirectories(build.resolve("resources/main"));
        Path bridge = Files.createDirectories(build.resolve("mcdp-bridges/classes"));

        List<Path> result = DevRootSafetyNet.appendBridgeClassesIfPresent(List.of(resources));

        assertTrue(result.contains(bridge));
    }

    @Test
    void noOpWhenBridgeDirAbsent(@TempDir Path tmp) throws IOException {
        Path build = Files.createDirectories(tmp.resolve("build"));
        Path javaMain = Files.createDirectories(build.resolve("classes/java/main"));
        // no mcdp-bridges/classes

        List<Path> result = DevRootSafetyNet.appendBridgeClassesIfPresent(List.of(javaMain));

        assertEquals(List.of(javaMain), result);
    }

    @Test
    void idempotentWhenBridgeDirAlreadyPresent(@TempDir Path tmp) throws IOException {
        Path build = Files.createDirectories(tmp.resolve("build"));
        Path javaMain = Files.createDirectories(build.resolve("classes/java/main"));
        Path bridge = Files.createDirectories(build.resolve("mcdp-bridges/classes"));

        List<Path> result = DevRootSafetyNet.appendBridgeClassesIfPresent(List.of(javaMain, bridge));

        assertEquals(2, result.size());
        assertEquals(List.of(javaMain, bridge), result);
    }

    @Test
    void noOpForUnrelatedPaths(@TempDir Path tmp) throws IOException {
        Path random = Files.createDirectories(tmp.resolve("random/dir"));

        List<Path> result = DevRootSafetyNet.appendBridgeClassesIfPresent(List.of(random));

        assertEquals(List.of(random), result);
    }

    @Test
    void doesNotAppendIfBridgeDirIsAFile(@TempDir Path tmp) throws IOException {
        Path build = Files.createDirectories(tmp.resolve("build"));
        Path javaMain = Files.createDirectories(build.resolve("classes/java/main"));
        // mcdp-bridges/classes exists but as a file → not a directory
        Path bridgeParent = Files.createDirectories(build.resolve("mcdp-bridges"));
        Files.writeString(bridgeParent.resolve("classes"), "decoy");

        List<Path> result = DevRootSafetyNet.appendBridgeClassesIfPresent(List.of(javaMain));

        assertEquals(List.of(javaMain), result);
        assertFalse(result.stream().anyMatch(p -> p.endsWith("classes")
                && p.getParent() != null && p.getParent().endsWith("mcdp-bridges")));
    }
}

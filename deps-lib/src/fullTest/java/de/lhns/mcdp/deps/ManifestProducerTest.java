package de.lhns.mcdp.deps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestProducerTest {

    /**
     * Opt-in integration test — hits Maven Central. Run via {@code ./gradlew :deps-lib:fullTest}.
     */
    @Test
    void resolvesCatsCoreTransitively(@TempDir Path tmp) throws IOException {
        Manifest manifest = ManifestProducer.produceSimple(
                tmp,
                "scala",
                List.of("org.typelevel:cats-core_3:2.13.0"));

        assertEquals("scala", manifest.lang());
        assertFalse(manifest.libraries().isEmpty(), "expected at least cats-core itself");

        boolean hasCatsCore = manifest.libraries().stream()
                .anyMatch(l -> l.coords().startsWith("org.typelevel:cats-core_3:2.13.0"));
        boolean hasCatsKernel = manifest.libraries().stream()
                .anyMatch(l -> l.coords().startsWith("org.typelevel:cats-kernel_3:"));
        boolean hasScalaLib = manifest.libraries().stream()
                .anyMatch(l -> l.coords().startsWith("org.scala-lang:scala3-library_3:")
                        || l.coords().startsWith("org.scala-lang:scala-library:"));

        assertTrue(hasCatsCore, "expected cats-core");
        assertTrue(hasCatsKernel, "expected transitive cats-kernel");
        assertTrue(hasScalaLib, "expected transitive scala-library");

        for (Manifest.Library lib : manifest.libraries()) {
            assertEquals(64, lib.sha256().length(), "SHA-256 must be 64 hex chars for " + lib.coords());
            assertTrue(lib.url().startsWith("https://repo1.maven.org/maven2/"),
                    "expected Maven Central URL, got: " + lib.url());
        }
    }
}

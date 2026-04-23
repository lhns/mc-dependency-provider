package io.github.mclibprovider.deps;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestIoTest {

    private static final String SHA1 = "a".repeat(64);
    private static final String SHA2 = "b".repeat(64);

    @Test
    void roundTripsASimpleManifest() throws IOException {
        Manifest original = new Manifest(
                "scala",
                List.of("com.example.api"),
                List.of(
                        new Manifest.Library("org.scala-lang:scala3-library_3:3.5.2",
                                "https://repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.5.2/scala3-library_3-3.5.2.jar",
                                SHA1),
                        new Manifest.Library("org.typelevel:cats-core_3:2.13.0",
                                "https://repo1.maven.org/maven2/org/typelevel/cats-core_3/2.13.0/cats-core_3-2.13.0.jar",
                                SHA2)));

        String serialized = ManifestIo.toString(original);
        Manifest parsed = ManifestIo.read(new ByteArrayInputStream(serialized.getBytes(StandardCharsets.UTF_8)));

        assertEquals(original, parsed);
    }

    @Test
    void parsesManifestWithoutSharedPackages() throws IOException {
        String toml = """
                lang = "java"

                [[libraries]]
                coords = "com.example:lib:1.0"
                url    = "https://example.com/lib.jar"
                sha256 = "%s"
                """.formatted(SHA1);

        Manifest parsed = ManifestIo.read(new ByteArrayInputStream(toml.getBytes(StandardCharsets.UTF_8)));
        assertEquals("java", parsed.lang());
        assertTrue(parsed.sharedPackages().isEmpty());
        assertEquals(1, parsed.libraries().size());
    }

    @Test
    void rejectsMissingLang() {
        String toml = """
                shared_packages = []
                """;
        assertThrows(IOException.class,
                () -> ManifestIo.read(new ByteArrayInputStream(toml.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void rejectsMalformedSha() {
        assertThrows(IllegalArgumentException.class,
                () -> new Manifest.Library("a:b:c", "https://x/y.jar", "short"));
    }
}

package io.github.mclibprovider.gradle;

import io.github.mclibprovider.deps.Manifest;
import io.github.mclibprovider.deps.ManifestIo;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class McLibProviderPluginTest {

    @Test
    void generatesManifestFromRuntimeClasspath(@TempDir Path tmp) throws IOException {
        // A tiny Gradle project depending on something that Gradle can reliably resolve
        // from Maven Central without pulling too much. We use tomlj (single jar, no transitive
        // deps beyond the Checker Framework annotations).
        Path buildFile = tmp.resolve("build.gradle.kts");
        Files.writeString(buildFile, """
                plugins {
                    `java-library`
                    id("io.github.mclibprovider")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation("org.tomlj:tomlj:1.1.1")
                }

                mclibprovider {
                    lang.set("java")
                    sharedPackages.add("com.example.api")
                }
                """);

        Files.writeString(tmp.resolve("settings.gradle.kts"), "rootProject.name = \"test-mod\"\n");

        BuildResult result = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("generateMcLibManifest", "--stacktrace")
                .withPluginClasspath()
                .build();

        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());

        Path manifestFile = tmp.resolve("build/mc-lib-provider/META-INF/mc-jvm-mod.toml");
        assertTrue(Files.exists(manifestFile), "manifest not created at " + manifestFile);

        Manifest manifest = ManifestIo.read(manifestFile);
        assertEquals("java", manifest.lang());
        assertEquals(1, manifest.sharedPackages().size());
        assertEquals("com.example.api", manifest.sharedPackages().get(0));

        // tomlj pulls in antlr4-runtime and checker-qual as transitive runtime deps.
        assertFalse(manifest.libraries().isEmpty(), "expected at least one library");
        boolean hasTomlj = manifest.libraries().stream()
                .anyMatch(l -> l.coords().startsWith("org.tomlj:tomlj:"));
        assertTrue(hasTomlj, "expected tomlj in libraries list: " + manifest.libraries());

        for (Manifest.Library l : manifest.libraries()) {
            assertEquals(64, l.sha256().length(), "SHA length for " + l.coords());
            assertTrue(l.url().startsWith("https://") || l.url().startsWith("http://"),
                    "expected http(s) URL for " + l.coords() + ": " + l.url());
        }
    }
}

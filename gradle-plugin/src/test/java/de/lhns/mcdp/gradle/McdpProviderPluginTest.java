package de.lhns.mcdp.gradle;

import de.lhns.mcdp.deps.Manifest;
import de.lhns.mcdp.deps.ManifestIo;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class McdpProviderPluginTest {

    @Test
    void generatesManifestFromRuntimeClasspath(@TempDir Path tmp) throws IOException {
        // A tiny Gradle project depending on something that Gradle can reliably resolve
        // from Maven Central without pulling too much. We use tomlj (single jar, no transitive
        // deps beyond the Checker Framework annotations).
        Path buildFile = tmp.resolve("build.gradle.kts");
        Files.writeString(buildFile, """
                plugins {
                    `java-library`
                    id("de.lhns.mcdp")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    mcLibImplementation("org.tomlj:tomlj:1.1.1")
                }

                mcdepprovider {
                    lang.set("java")
                    sharedPackages.add("com.example.api")
                }
                """);

        Files.writeString(tmp.resolve("settings.gradle.kts"), "rootProject.name = \"test-mod\"\n");

        BuildResult result = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("generateMcdpManifest", "--stacktrace")
                .withPluginClasspath()
                .build();

        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());

        Path manifestFile = tmp.resolve("build/mcdepprovider/META-INF/mcdepprovider.toml");
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

    /**
     * Exercises {@link RunTaskClasspathPatch} via a synthetic {@code JavaExec} named
     * {@code runServer}. JavaExec will fail when it tries to invoke a non-existent main class,
     * which is exactly what we want: the patch's {@code doFirst} has already run by then, so the
     * lifecycle log reports the strip count. We verify the count matches the number of entries
     * in the generated manifest.
     */
    @Test
    void stripsManifestJarsFromRunTaskClasspath(@TempDir Path tmp) throws IOException {
        Path buildFile = tmp.resolve("build.gradle.kts");
        Files.writeString(buildFile, """
                import org.gradle.api.tasks.JavaExec

                plugins {
                    `java-library`
                    id("de.lhns.mcdp")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    mcLibImplementation("org.tomlj:tomlj:1.1.1")
                }

                mcdepprovider {
                    lang.set("java")
                }

                // Synthetic run task that mimics ModDevGradle's runServer shape. The patch's
                // doFirst runs before the main action; once it logs the strip count we're done.
                tasks.register<JavaExec>("runServer") {
                    classpath = sourceSets["main"].runtimeClasspath
                    mainClass.set("non.existent.Main")
                }
                """);

        Files.writeString(tmp.resolve("settings.gradle.kts"), "rootProject.name = \"test-mod\"\n");

        BuildResult result = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("runServer", "--stacktrace")
                .withPluginClasspath()
                .buildAndFail();

        String out = result.getOutput();
        // Lifecycle log from RunTaskClasspathPatch. Format: "stripped N manifest-listed jars from <task> classpath".
        assertTrue(out.contains("stripped") && out.contains("manifest-listed jars from runServer"),
                "patch lifecycle log missing; output: " + out);

        // Parse the strip count and cross-check it against the generated manifest.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("stripped (\\d+) manifest-listed jars").matcher(out);
        assertTrue(m.find(), "expected 'stripped N manifest-listed jars' in log");
        int stripped = Integer.parseInt(m.group(1));

        Path manifestFile = tmp.resolve("build/mcdepprovider/META-INF/mcdepprovider.toml");
        assertTrue(Files.exists(manifestFile), "manifest should have been generated");
        Manifest manifest = ManifestIo.read(manifestFile);
        assertEquals(manifest.libraries().size(), stripped,
                "strip count should equal manifest library count; manifest=" + manifest.libraries());
    }
}

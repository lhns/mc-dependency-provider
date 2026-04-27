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
                    mcdepImplementation("org.tomlj:tomlj:1.1.1")
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
                    mcdepImplementation("org.tomlj:tomlj:1.1.1")
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

    /**
     * Mixin codegen must find {@code .java} mixins joint-compiled by scalac. When the {@code scala}
     * plugin is applied and a {@code .java} mixin source lives under {@code src/main/scala/}, scalac
     * compiles it into {@code build/classes/scala/main/} and {@code compileJava}'s output dir stays
     * empty. The bridge codegen task's input is wired to {@code main.output.classesDirs} (covering
     * scala + kotlin + java), so the rewrite still finds the class.
     *
     * <p>End-to-end proof of the wiring change. Pulls scala3-library from Maven Central — same
     * network assumption as the manifest test above.</p>
     */
    @Test
    void rewritesJavaMixinJointCompiledByScala(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("settings.gradle.kts"), "rootProject.name = \"scala_mixin\"\n");
        Files.writeString(tmp.resolve("build.gradle.kts"), """
                plugins {
                    `java-library`
                    scala
                    id("de.lhns.mcdp")
                }

                // group makes the plugin compute a proper default bridgePackage
                // (com.example.scala_mixin.mcdp_mixin_bridges); without it the package is empty
                // and the emitted bridge file path resolves to drive-root on Windows.
                group = "com.example"

                repositories {
                    mavenCentral()
                }

                dependencies {
                    // scalac needs scala-library on the compile classpath to do joint compilation.
                    implementation("org.scala-lang:scala3-library_3:3.3.4")
                }

                mcdepprovider {
                    lang.set("scala")
                    sharedPackages.add("com.example.api")
                }
                """);

        // Mixin under src/main/scala/ — joint-compiled by scalac into build/classes/scala/main/.
        // The class calls into a mod-private static helper; scanner classifies as REWRITABLE.
        Path mixinSrc = tmp.resolve("src/main/scala/com/example/mixin/MyMixin.java");
        Files.createDirectories(mixinSrc.getParent());
        Files.writeString(mixinSrc, """
                package com.example.mixin;
                public class MyMixin {
                    public static int handler(int x) { return com.example.modcode.Helper.doubleIt(x) + 1; }
                }
                """);
        // Helper also under src/main/scala/ — same joint-compilation path.
        Path helperSrc = tmp.resolve("src/main/scala/com/example/modcode/Helper.java");
        Files.createDirectories(helperSrc.getParent());
        Files.writeString(helperSrc, """
                package com.example.modcode;
                public class Helper {
                    public static int doubleIt(int x) { return x * 2; }
                }
                """);

        // Mixin config declaring the mixin.
        Path resources = tmp.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("test.mixins.json"),
                "{\"package\":\"com.example.mixin\",\"mixins\":[\"MyMixin\"]}");

        BuildResult result = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("generateMcdpMixinBridges", "--stacktrace")
                .withPluginClasspath()
                .build();

        String out = result.getOutput();
        assertTrue(out.contains("BUILD SUCCESSFUL"), out);

        // Sanity: scalac wrote the bytecode to the scala output dir, not java.
        Path scalaOut = tmp.resolve("build/classes/scala/main/com/example/mixin/MyMixin.class");
        assertTrue(Files.isRegularFile(scalaOut),
                "scalac should have produced " + scalaOut + " — joint-compilation premise broken");

        // Codegen report lists the rewritten mixin (proving the task found it under scala output).
        Path report = tmp.resolve("build/mcdp-mixin-bridges/report.txt");
        assertTrue(Files.exists(report), "codegen report missing at " + report);
        String reportText = Files.readString(report);
        assertTrue(reportText.contains("rewritten mixins (1)"),
                "expected 1 rewritten mixin in report:\n" + reportText);
        assertTrue(reportText.contains("com.example.mixin.MyMixin"),
                "expected MyMixin in report:\n" + reportText);

        // Rewritten class file in the codegen output.
        Path rewritten = tmp.resolve("build/mcdp-mixin-bridges/classes/com/example/mixin/MyMixin.class");
        assertTrue(Files.isRegularFile(rewritten), "rewritten Mixin missing at " + rewritten);
    }
}

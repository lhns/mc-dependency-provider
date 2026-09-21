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
                    // Default is empty (see McdpProviderExtension docstring). Opt in here so
                    // the patch fires and we can verify the strip-count behavior.
                    patchRunTasks.set(listOf("runServer"))
                }

                // Synthetic run task that mimics ModDevGradle's runServer shape. The patch's
                // doFirst runs before the main action; once it logs the strip count we're done.
                tasks.register<JavaExec>("runServer") {
                    classpath = sourceSets["main"].runtimeClasspath
                    mainClass.set("Noop")
                    doLast {
                        file("patched-classpath.txt").writeText(
                            classpath.files.joinToString(" ") { it.name })
                    }
                }
                """);

        Files.writeString(tmp.resolve("settings.gradle.kts"), "rootProject.name = \"test-mod\"\n");

        Path noop = tmp.resolve("src/main/java/Noop.java");
        Files.createDirectories(noop.getParent());
        Files.writeString(noop, "public final class Noop { public static void main(String[] a) {} }\n");

        BuildResult result = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("runServer", "--stacktrace")
                .withPluginClasspath()
                .build();

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

        // The assertion that matters: every manifest-listed artifact is absent from the classpath
        // the task actually ran with. ADR-0007 parity is about the classpath, not about the log --
        // a patch that counted correctly and then assigned the unfiltered collection passed before.
        String patched = Files.readString(tmp.resolve("patched-classpath.txt"));
        assertFalse(manifest.libraries().isEmpty(), "fixture must resolve at least one mcdep");
        for (Manifest.Library lib : manifest.libraries()) {
            String[] gav = lib.coords().split(":");
            String jarName = gav[1] + "-" + gav[2] + ".jar";
            assertFalse(patched.contains(jarName),
                    "manifest-listed jar " + jarName + " survived the patch; classpath:\n" + patched);
        }
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
                // (com.example.scala_mixin.mcdp_bridges); without it the package is empty
                // and the emitted bridge file path resolves to drive-root on Windows.
                group = "com.example"

                repositories {
                    mavenCentral()
                    maven { url = uri("https://repo.spongepowered.org/repository/maven-public/") }
                }

                dependencies {
                    // scalac needs scala-library on the compile classpath to do joint compilation.
                    implementation("org.scala-lang:scala3-library_3:3.3.4")
                    // Sponge Mixin annotation — needed at compile time so the seed annotation
                    // resolves on the @Mixin-tagged source. Runtime is unused; this is a compile
                    // dependency only for the codegen test to have a real Mixin annotation type.
                    compileOnly("org.spongepowered:mixin:0.8.5")
                }

                mcdepprovider {
                    lang.set("scala")
                    sharedPackages.add("com.example.api")
                }
                """);

        // Mixin under src/main/scala/ — joint-compiled by scalac into build/classes/scala/main/.
        // The class calls into a mod-private static helper; scanner classifies as REWRITABLE.
        // @org.spongepowered.asm.mixin.Mixin on the class header lets the codegen's annotation
        // seed (ADR-0021) discover this class.
        Path mixinSrc = tmp.resolve("src/main/scala/com/example/mixin/MyMixin.java");
        Files.createDirectories(mixinSrc.getParent());
        Files.writeString(mixinSrc, """
                package com.example.mixin;
                @org.spongepowered.asm.mixin.Mixin(Object.class)
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
                .withArguments("generateMcdpBridges", "--stacktrace")
                .withPluginClasspath()
                .build();

        String out = result.getOutput();
        assertTrue(out.contains("BUILD SUCCESSFUL"), out);

        // Sanity: scalac wrote the bytecode to the scala output dir, not java.
        Path scalaOut = tmp.resolve("build/classes/scala/main/com/example/mixin/MyMixin.class");
        assertTrue(Files.isRegularFile(scalaOut),
                "scalac should have produced " + scalaOut + " — joint-compilation premise broken");

        // Codegen report lists the rewritten mixin (proving the task found it under scala output).
        Path report = tmp.resolve("build/mcdp-bridges/report.txt");
        assertTrue(Files.exists(report), "codegen report missing at " + report);
        String reportText = Files.readString(report);
        assertTrue(reportText.contains("rewritten mixins (1)"),
                "expected 1 rewritten mixin in report:\n" + reportText);
        assertTrue(reportText.contains("com.example.mixin.MyMixin"),
                "expected MyMixin in report:\n" + reportText);

        // Rewritten in place over the scala output (joint compilation put the .class there).
        // The codegen overwrites the original file rather than emitting a second copy under
        // build/mcdp-bridges/classes/ — see BridgeCodegenTask comment for why.
        Path rewritten = tmp.resolve("build/classes/scala/main/com/example/mixin/MyMixin.class");
        assertTrue(Files.isRegularFile(rewritten),
                "in-place rewritten MyMixin missing at " + rewritten);
        // Original javac output for the trivial fixture is small; rewritten has LOGIC field
        // + <clinit> + stack-juggle, strictly larger.
        assertTrue(Files.size(rewritten) > 400,
                "rewritten size " + Files.size(rewritten) + " not larger than original — in-place rewrite didn't fire?");
    }

    /**
     * Regression for the mc-fluid-physics 2026-05-02 production crash: an incremental
     * compileScala (or compileJava) re-run between two builds overwrites our in-place rewrite
     * with fresh ORIGINAL bytecode. Before the fix, bridgeTask's input fingerprint matched
     * what Gradle recorded at the start of the previous run (both = original) and its outputs
     * (mcdp-bridges/classes + manifest + report) were unchanged, so Gradle skipped bridgeTask
     * as up-to-date. The jar then packed the un-rewritten mixin alongside the previous run's
     * bridges — every call site through the rewritten body fell through to the parent loader.
     *
     * <p>Fix: {@code outputs.upToDateWhen { false }} on BridgeCodegenTask. This test simulates
     * the upstream-overwrite condition and asserts the rewritten file persists across two
     * Gradle invocations.
     */
    @Test
    void bridgeTaskRerunsAfterUpstreamOverwrite(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("settings.gradle.kts"), "rootProject.name = \"rerun_test\"\n");
        Files.writeString(tmp.resolve("build.gradle.kts"), """
                plugins {
                    `java-library`
                    scala
                    id("de.lhns.mcdp")
                }
                group = "com.example"
                repositories {
                    mavenCentral()
                    maven { url = uri("https://repo.spongepowered.org/repository/maven-public/") }
                }
                dependencies {
                    implementation("org.scala-lang:scala3-library_3:3.3.4")
                    compileOnly("org.spongepowered:mixin:0.8.5")
                }
                mcdepprovider {
                    lang.set("scala")
                }
                """);

        Path mixinSrc = tmp.resolve("src/main/scala/com/example/mixin/MyMixin.java");
        Files.createDirectories(mixinSrc.getParent());
        Files.writeString(mixinSrc, """
                package com.example.mixin;
                @org.spongepowered.asm.mixin.Mixin(Object.class)
                public class MyMixin {
                    public static int handler(int x) { return com.example.modcode.Helper.doubleIt(x) + 1; }
                }
                """);
        Path helperSrc = tmp.resolve("src/main/scala/com/example/modcode/Helper.java");
        Files.createDirectories(helperSrc.getParent());
        Files.writeString(helperSrc, """
                package com.example.modcode;
                public class Helper {
                    public static int doubleIt(int x) { return x * 2; }
                }
                """);

        // Build 1: clean build, bridgeTask rewrites in place.
        BuildResult build1 = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("generateMcdpBridges", "--stacktrace")
                .withPluginClasspath()
                .build();
        assertTrue(build1.getOutput().contains("BUILD SUCCESSFUL"), build1.getOutput());

        Path mixinClass = tmp.resolve("build/classes/scala/main/com/example/mixin/MyMixin.class");
        assertTrue(Files.isRegularFile(mixinClass), "mixin class missing after Build 1");
        long rewrittenSize = Files.size(mixinClass);

        // Stash the original (un-rewritten) bytecode by re-compiling MyMixin.java directly with
        // javac — this gives us bytes equivalent to what an incremental compileScala would have
        // produced. We don't shell out; we use Gradle to recompile by deleting the rewritten class
        // and re-running compileScala, which is exactly what happens in the production scenario.
        Files.delete(mixinClass);
        BuildResult recompile = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("compileScala", "--stacktrace")
                .withPluginClasspath()
                .build();
        assertTrue(recompile.getOutput().contains("BUILD SUCCESSFUL"), recompile.getOutput());
        assertTrue(Files.isRegularFile(mixinClass), "compileScala didn't restore the class file");
        long originalSize = Files.size(mixinClass);
        // Sanity: the recompiled file is ORIGINAL bytecode, smaller than the rewritten one.
        assertTrue(originalSize < rewrittenSize,
                "expected recompile to produce smaller (un-rewritten) class: original="
                        + originalSize + " rewritten=" + rewrittenSize);

        // Build 2: same task. Pre-fix, bridgeTask is skipped UP-TO-DATE because input fingerprint
        // (= original bytecode now) matches what Gradle recorded at the start of Build 1, and its
        // outputs (mcdp-bridges + manifest) are unchanged. Post-fix (upToDateWhen { false }), it
        // re-runs and re-rewrites the file in place.
        BuildResult build2 = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("generateMcdpBridges", "--stacktrace")
                .withPluginClasspath()
                .build();
        assertTrue(build2.getOutput().contains("BUILD SUCCESSFUL"), build2.getOutput());

        // The fix's signature: bridgeTask should not show as UP-TO-DATE in Build 2.
        assertFalse(build2.getOutput().contains("> Task :generateMcdpBridges UP-TO-DATE"),
                "bridgeTask should not be UP-TO-DATE — that's the bug we're guarding against:\n" + build2.getOutput());

        // The rewrite must have re-applied: file size is back to the rewritten size.
        long rebuiltSize = Files.size(mixinClass);
        assertEquals(rewrittenSize, rebuiltSize,
                "mixin class size after Build 2 should match Build 1's rewritten size; "
                        + "got " + rebuiltSize + " expected " + rewrittenSize
                        + " — bridgeTask probably skipped and the un-rewritten class shipped.");
    }

    /**
     * Back-to-back reruns with NO source change must not leave the mod with rewritten mixins and
     * no bridges. {@code BridgeCodegenTask} cleans {@code build/mcdp-bridges/classes} and
     * {@code .../resources} on every run and regenerates them from a scan of the compile outputs
     * — but the compile outputs hold the PREVIOUS run's rewritten bytecode, which scans as
     * SKIPPED (the cross-classloader refs are already bridged). Before the source-cache fix the
     * second run therefore wiped both dirs and put nothing back: the rewritten mixin's
     * {@code INVOKEINTERFACE HelperBridge.config()} had no {@code HelperBridge} class and no
     * {@code META-INF/mcdp-bridges.toml} to register it → {@code NoClassDefFoundError} in-game.
     *
     * <p>It used to survive only by accident: the in-place rewrite dirties the compile task's
     * output snapshot, so the next build recompiled and handed the codegen original bytecode
     * again. Any build where that recompile doesn't happen (FROM-CACHE, partial restore, a task
     * graph without the compile task) dropped the bridges silently. This test reruns
     * {@code generateMcdpBridges} with the compile task excluded (and again plainly), touching
     * no source, and asserts the manifest and the emitted {@code *Bridge.class} files survive.
     *
     * <p>Distinct from {@link #bridgeTaskRerunsAfterUpstreamOverwrite}, which deliberately
     * deletes the class and re-runs {@code compileScala} first — i.e. it exercises the path
     * where the originals ARE restored, and asserts only the rewritten file's size.
     */
    @Test
    void bridgeOutputsSurvivePlainRerun(@TempDir Path tmp) throws IOException {
        writeBridgeFixture(tmp, "plain_rerun");

        BuildResult build1 = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("generateMcdpBridges", "--stacktrace")
                .withPluginClasspath()
                .build();
        assertTrue(build1.getOutput().contains("BUILD SUCCESSFUL"), build1.getOutput());
        assertBridgeOutputsPresent(tmp, "after build 1");

        // Rerun with the compile task excluded. `-x compileJava` is the cheap, deterministic
        // stand-in for every real-world way the compile task fails to hand the codegen original
        // bytecode: a FROM-CACHE compile, a partial output restore, or an invocation whose task
        // graph simply doesn't include it. Without it the test proves nothing — the in-place
        // rewrite dirties compileJava's output snapshot, so a plain rerun recompiles and the
        // codegen gets its originals back by luck.
        BuildResult build2 = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("generateMcdpBridges", "-x", "compileJava", "--stacktrace")
                .withPluginClasspath()
                .build();
        assertTrue(build2.getOutput().contains("BUILD SUCCESSFUL"), build2.getOutput());
        assertBridgeOutputsPresent(tmp, "after build 2 (rerun without a recompile)");

        // And a plain rerun, which is what a user actually types.
        BuildResult build3 = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("generateMcdpBridges", "--stacktrace")
                .withPluginClasspath()
                .build();
        assertTrue(build3.getOutput().contains("BUILD SUCCESSFUL"), build3.getOutput());
        assertBridgeOutputsPresent(tmp, "after build 3 (plain rerun)");
    }

    /**
     * ADR-0024's over-share validator must not fire on the codegen's OWN output. The plugin
     * auto-adds the bridge package to {@code sharedPackages}, and generated bridge methods name
     * mod-private types in their descriptors by construction
     * ({@code config()Lcom/example/modcode/Config;}) — that is the whole point of a bridge.
     * Scanning them as if they were user code produced an OVER_SHARE diagnostic against a file
     * the user cannot edit, whose only suggested remedy was to share the mod's own package: the
     * exact over-share ADR-0024 exists to prevent.
     */
    @Test
    void validateSharedPackagesIgnoresGeneratedBridges(@TempDir Path tmp) throws IOException {
        writeBridgeFixture(tmp, "validate_bridges");

        BuildResult result = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("validateSharedPackages", "--stacktrace")
                .withPluginClasspath()
                .build();

        String out = result.getOutput();
        assertTrue(out.contains("BUILD SUCCESSFUL"), out);
        assertFalse(out.contains("mcdp_bridges") && out.contains("shared-package class"),
                "validator flagged a generated bridge:\n" + out);

        // Premise check: the bridge really does name a mod-private type in its descriptor, so
        // this test would fail if the codegen output were fed back into the validator.
        // Bridge names are <SimpleName>_<hash> so two same-named targets in different
        // packages can't collide, so derive it rather than hardcoding.
        String helperBridge = de.lhns.mcdp.gradle.bridges.BridgeRewriter
                .bridgeSimpleName("com/example/modcode/Helper") + "Bridge.class";
        Path bridgeClass = tmp.resolve(
                "build/mcdp-bridges/classes/com/example/validate_bridges/mcdp_bridges/" + helperBridge);
        assertTrue(Files.isRegularFile(bridgeClass), "expected generated bridge at " + bridgeClass);
        String constantPool = new String(Files.readAllBytes(bridgeClass),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(constantPool.contains("com/example/modcode/Config"),
                "fixture no longer produces a bridge naming a mod-private type — premise broken");
    }

    /**
     * ADR-0025 makes run-task stripping opt-in, so {@link RunTaskClasspathPatch} only fires for
     * users who asked for strict prod parity — and its {@code doFirst} used to capture
     * {@code Project}, the manifest {@code TaskProvider} and the run task itself, all of which
     * fail to serialize under {@code --configuration-cache}. That put the one advertised
     * diagnostic path out of reach for any build with the configuration cache on.
     */
    @Test
    void runTaskPatchIsConfigurationCacheCompatible(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("settings.gradle.kts"), "rootProject.name = \"cc_test\"\n");
        Files.writeString(tmp.resolve("build.gradle.kts"), """
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
                    patchRunTasks.set(listOf("runServer"))
                }

                tasks.register<JavaExec>("runServer") {
                    classpath = sourceSets["main"].runtimeClasspath
                    mainClass.set("non.existent.Main")
                }
                """);

        // Fails on the missing main class, as in stripsManifestJarsFromRunTaskClasspath — the
        // patch has already run by then. What we assert is that the failure is THAT one and not
        // a configuration-cache serialization problem.
        BuildResult result = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("runServer", "--configuration-cache", "--stacktrace")
                .withPluginClasspath()
                .buildAndFail();

        String out = result.getOutput();
        assertFalse(out.contains("problems were found storing the configuration cache")
                        || out.contains("configuration cache problem"),
                "configuration cache violation in the run-task patch:\n" + out);
        assertTrue(out.contains("manifest-listed jars from runServer"),
                "patch didn't run under --configuration-cache:\n" + out);
    }

    /** Minimal project whose mixin forces a bridge with a mod-private type in its descriptor. */
    private static void writeBridgeFixture(Path tmp, String projectName) throws IOException {
        Files.writeString(tmp.resolve("settings.gradle.kts"),
                "rootProject.name = \"" + projectName + "\"\n");
        Files.writeString(tmp.resolve("build.gradle.kts"), """
                plugins {
                    `java-library`
                    id("de.lhns.mcdp")
                }
                group = "com.example"
                repositories {
                    mavenCentral()
                    maven { url = uri("https://repo.spongepowered.org/repository/maven-public/") }
                }
                dependencies {
                    compileOnly("org.spongepowered:mixin:0.8.5")
                }
                mcdepprovider {
                    lang.set("java")
                }
                """);

        Path src = tmp.resolve("src/main/java");
        Files.createDirectories(src.resolve("com/example/mixin"));
        Files.createDirectories(src.resolve("com/example/modcode"));
        // Returning a mod-private type puts com.example.modcode.Config into the generated
        // bridge's method descriptor — the shape that tripped the over-share validator.
        Files.writeString(src.resolve("com/example/mixin/MyMixin.java"), """
                package com.example.mixin;
                @org.spongepowered.asm.mixin.Mixin(Object.class)
                public class MyMixin {
                    // Returns int, not Config: a mixin's own method descriptor is merged onto
                    // the target class, so naming a mod-private type there is itself an error
                    // (BridgeScanner rejects it). The mod-private type must appear only in the
                    // CALLED method's descriptor -- which is what puts it on the generated
                    // bridge interface, the case these tests exercise.
                    public static int handler() {
                        return com.example.modcode.Helper.config().value;
                    }
                }
                """);
        Files.writeString(src.resolve("com/example/modcode/Config.java"), """
                package com.example.modcode;
                public class Config { public int value = 1; }
                """);
        Files.writeString(src.resolve("com/example/modcode/Helper.java"), """
                package com.example.modcode;
                public class Helper {
                    public static Config config() { return new Config(); }
                }
                """);
    }

    private static void assertBridgeOutputsPresent(Path tmp, String phase) throws IOException {
        Path manifest = tmp.resolve("build/mcdp-bridges/resources/META-INF/mcdp-bridges.toml");
        assertTrue(Files.isRegularFile(manifest),
                "bridge manifest missing " + phase + " at " + manifest);
        assertTrue(Files.readString(manifest).contains("[[bridge]]"),
                "bridge manifest has no entries " + phase + ":\n" + Files.readString(manifest));

        Path classesDir = tmp.resolve("build/mcdp-bridges/classes");
        assertTrue(Files.isDirectory(classesDir), "bridge classes dir missing " + phase);
        try (java.util.stream.Stream<Path> walk = Files.walk(classesDir)) {
            java.util.List<Path> bridges = walk
                    .filter(p -> p.getFileName().toString().endsWith("Bridge.class"))
                    .toList();
            assertFalse(bridges.isEmpty(),
                    "no *Bridge.class emitted " + phase + " under " + classesDir);
        }
    }
}

package de.lhns.mcdp.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one test that executes the RELOCATED ASM.
 *
 * <p>ASM is shaded into {@code de.lhns.mcdp.gradle.shaded.asm} because Gradle's own
 * {@code org.objectweb.asm} otherwise decided which ASM the codegen ran against — which broke on
 * a JDK 25 toolchain the daemon's ASM could not read. Two things guard that relocation today and
 * neither one runs the relocated code: {@code verifyAsmRelocated} only inspects jar entries, and
 * {@code pluginUnderTestMetadata} hands TestKit the <em>un-relocated</em> {@code bundle}
 * configuration, so every other integration test in this module resolves {@code org.objectweb.asm}
 * and would keep passing if shadow had missed a reference.
 *
 * <p>Shadow rewrites bytecode references and plain string constants, but it cannot rewrite a class
 * name assembled at runtime (from fragments, config, or a resource it does not recognise). Such a
 * reference survives {@code check} and the whole suite, then throws
 * {@code ClassNotFoundException}/{@code NoClassDefFoundError} on a real consumer's first build,
 * because {@code org.objectweb.asm} is not among the packages Gradle exports to plugin
 * classloaders. This test closes that hole by pointing TestKit at the shadow jar itself and
 * running a build that actually emits a bridge.
 *
 * <p>It is the slowest test in the module: it forces {@code shadowJar} and then compiles a fixture
 * that resolves Mixin from the network. If the wall-clock cost ever needs managing, tag it
 * ({@code @Tag("slow")}) and give CI a job that runs that tag — do not exclude it by default, a
 * relocation check nobody runs is the failure mode it exists to remove.
 */
class ShadowJarCodegenTest {

    /** Set by the `test` task in gradle-plugin/build.gradle.kts, which also builds the jar. */
    private static final String SHADOW_JAR_PROPERTY = "mcdp.shadow.jar";

    @Test
    void codegenRunsAgainstTheRelocatedAsmInTheShadowJar(@TempDir Path tmp) throws IOException {
        Path shadowJar = shadowJarUnderTest();
        writeBridgeFixture(tmp);

        // withPluginClasspath(jar) instead of withPluginClasspath(): the shadow jar is
        // self-contained (plugin classes, the plugin descriptor under META-INF/gradle-plugins,
        // :deps-lib and the relocated ASM), so adding anything else would only risk putting an
        // un-relocated org.objectweb.asm back on the classpath and defeating the test.
        BuildResult result = GradleRunner.create()
                .withProjectDir(tmp.toFile())
                .withArguments("generateMcdpBridges", "--stacktrace")
                .withPluginClasspath(List.of(shadowJar.toFile()))
                .build();

        String out = result.getOutput();
        assertTrue(out.contains("BUILD SUCCESSFUL"), out);
        assertFalse(out.contains("org.objectweb.asm"),
                "codegen reached the un-relocated ASM package under the shadow jar:\n" + out);

        // Build success alone would also be reported if the codegen had quietly emitted nothing,
        // so assert on the artifacts the relocated ASM had to produce.
        Path manifest = tmp.resolve("build/mcdp-bridges/resources/META-INF/mcdp-bridges.toml");
        assertTrue(Files.isRegularFile(manifest), "bridge manifest missing at " + manifest);
        String manifestText = Files.readString(manifest);
        assertTrue(manifestText.contains("[[bridge]]"),
                "bridge manifest has no entries:\n" + manifestText);

        Path classesDir = tmp.resolve("build/mcdp-bridges/classes");
        assertTrue(Files.isDirectory(classesDir), "bridge classes dir missing at " + classesDir);
        try (Stream<Path> walk = Files.walk(classesDir)) {
            List<Path> bridges = walk
                    .filter(p -> p.getFileName().toString().endsWith("Bridge.class"))
                    .toList();
            assertFalse(bridges.isEmpty(), "no *Bridge.class emitted under " + classesDir);
        }
    }

    private static Path shadowJarUnderTest() {
        String path = System.getProperty(SHADOW_JAR_PROPERTY);
        // Deliberately a failure, not an assumption: a silent skip here would restore exactly the
        // blind spot this test exists to remove. Running it outside Gradle needs
        // -D<property>=<path to the shadow jar>.
        assertNotNull(path, "system property " + SHADOW_JAR_PROPERTY
                + " is unset; run this test through the Gradle `test` task, which builds the "
                + "shadow jar and passes its path");
        Path jar = Path.of(path);
        assertTrue(Files.isRegularFile(jar), "shadow jar missing at " + jar);
        return jar;
    }

    /**
     * Same shape as the codegen fixtures in {@link McdpProviderPluginTest}: a {@code @Mixin}-tagged
     * class calling a mod-private type, which is what forces a bridge to be emitted. Duplicated
     * rather than shared so this test stays a self-contained statement about the shadow jar.
     */
    private static void writeBridgeFixture(Path tmp) throws IOException {
        Files.writeString(tmp.resolve("settings.gradle.kts"),
                "rootProject.name = \"shadow_codegen\"\n");
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
        // The mod-private type must appear in the CALLED method's descriptor, not the mixin's own
        // — a mixin method's descriptor is merged onto the target class, which BridgeScanner
        // rejects. Helper.config() returning Config is what puts Config on the bridge interface.
        Files.writeString(src.resolve("com/example/mixin/MyMixin.java"), """
                package com.example.mixin;
                @org.spongepowered.asm.mixin.Mixin(Object.class)
                public class MyMixin {
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
}

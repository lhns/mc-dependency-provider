package io.github.mclibprovider.api;

import io.github.mclibprovider.core.ModClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McLibProviderTest {

    @BeforeEach
    void reset() {
        McLibProvider.resetForTests();
    }

    @AfterEach
    void cleanup() {
        McLibProvider.resetForTests();
    }

    /** The "bridge interface" — declared in the test classloader (simulates shared_packages). */
    public interface Logic {
        int compute();
    }

    /** The mixin class — test classloader. In production this lives in a mod jar but on a shared_packages prefix. */
    @McLibMixin(impl = "io.github.mclibprovider.api.FakeImpl", modId = "test-mod")
    public static class FakeMixin {
        public static Logic load() {
            return McLibProvider.loadMixinImpl(Logic.class);
        }
    }

    @Test
    void loadsAndInstantiatesImplFromModLoader(@TempDir Path tmp) throws Exception {
        // Write a compiled FakeImpl class file that implements Logic and returns 42.
        // We produce it by compiling it in-memory via the real javac; simpler than hand-rolling bytecode
        // with constants pools.
        Path implJar = tmp.resolve("impl.jar");
        compileFakeImplJar(implJar);

        // Simulate the mod classloader: parent is the test loader (which has Logic and FakeMixin).
        try (ModClassLoader mod = new ModClassLoader(
                "test-mod",
                new URL[]{implJar.toUri().toURL()},
                getClass().getClassLoader(),
                List.of())) {

            McLibProvider.registerMod("test-mod", mod);

            Logic l = FakeMixin.load();
            assertNotNull(l);
            assertEquals(42, l.compute());

            // Second call returns the cached instance.
            assertSame(l, FakeMixin.load());
        }
    }

    @Test
    void failsWithoutRegistration() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, FakeMixin::load);
        assertTrue(ex.getMessage().contains("no ModClassLoader"), ex.getMessage());
    }

    @Test
    void failsWhenMixinClassIsNotAnnotated() {
        class Bare {
            static Logic load() {
                return McLibProvider.loadMixinImpl(Logic.class);
            }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class, Bare::load);
        assertTrue(ex.getMessage().contains("missing @McLibMixin"), ex.getMessage());
    }

    /**
     * Compile a tiny Java source that implements {@link Logic} and package it into a jar.
     * Uses {@link javax.tools.JavaCompiler} so we don't have to emit class-file bytes by hand.
     */
    private static void compileFakeImplJar(Path jarPath) throws Exception {
        Path workDir = Files.createTempDirectory("mclib-impl-");
        Path src = workDir.resolve("FakeImpl.java");
        Files.writeString(src, """
                package io.github.mclibprovider.api;
                public class FakeImpl implements io.github.mclibprovider.api.McLibProviderTest.Logic {
                    public FakeImpl() {}
                    public int compute() { return 42; }
                }
                """);

        Path classes = workDir.resolve("classes");
        Files.createDirectories(classes);

        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK required to run this test");
        int rc = compiler.run(null, null, null,
                "-d", classes.toString(),
                "-classpath", System.getProperty("java.class.path"),
                src.toString());
        assertEquals(0, rc, "javac failed");

        Path classFile = classes.resolve("io/github/mclibprovider/api/FakeImpl.class");
        byte[] bytes = Files.readAllBytes(classFile);

        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jarPath))) {
            var entry = new java.util.jar.JarEntry("io/github/mclibprovider/api/FakeImpl.class");
            out.putNextEntry(entry);
            out.write(bytes);
            out.closeEntry();
        }
    }
}

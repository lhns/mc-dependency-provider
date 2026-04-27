package de.lhns.mcdp.api;

import de.lhns.mcdp.core.ModClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McdpProviderTest {

    @BeforeEach
    void reset() {
        McdpProvider.resetForTests();
    }

    @AfterEach
    void cleanup() {
        McdpProvider.resetForTests();
    }

    /** The "bridge interface" — declared in the test classloader (simulates shared_packages). */
    public interface Logic {
        int compute();
    }

    /** The mixin class — test classloader. In production this lives in a mod jar but on a shared_packages prefix. */
    @McdpMixin(impl = "de.lhns.mcdp.api.FakeImpl", modId = "test-mod")
    public static class FakeMixin {
        public static Logic load() {
            return McdpProvider.loadMixinImpl(Logic.class);
        }
    }

    /**
     * Mixin without an explicit {@code modId} — simulates the common case where a mod author
     * relies on the platform adapter having pre-registered {@code registerMixinOwner} instead of
     * spelling the modId at the callsite.
     */
    @McdpMixin(impl = "de.lhns.mcdp.api.FakeImpl")
    public static class FqnOnlyMixin {
        public static Logic load() {
            return McdpProvider.loadMixinImpl(Logic.class);
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

            McdpProvider.registerMod("test-mod", mod);

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

    /**
     * With two mods registered, an annotation-less-modId mixin must route via the
     * {@code registerMixinOwner} FQN map rather than hitting the size-1 fallback (which would
     * throw in multi-mod configurations). Regression coverage for the multi-mod Mixin bug
     * documented in {@code docs/pitfalls.md} and ADR-0008.
     */
    @Test
    void routesViaMixinOwnerFqnMapAcrossMultipleMods(@TempDir Path tmp) throws Exception {
        Path implJar = tmp.resolve("impl.jar");
        compileFakeImplJar(implJar);

        try (ModClassLoader decoy = new ModClassLoader(
                "other-mod", new URL[0], getClass().getClassLoader(), List.of());
             ModClassLoader real = new ModClassLoader(
                     "real-mod",
                     new URL[]{implJar.toUri().toURL()},
                     getClass().getClassLoader(),
                     List.of())) {

            McdpProvider.registerMod("other-mod", decoy);
            McdpProvider.registerMod("real-mod", real);
            McdpProvider.registerMixinOwner(FqnOnlyMixin.class.getName(), "real-mod");

            Logic l = FqnOnlyMixin.load();
            assertNotNull(l);
            assertEquals(42, l.compute());
        }
    }

    /**
     * Without a pre-registered FQN mapping and with two mods registered, the size-1 fallback
     * can't pick a winner — the caller must either set {@code modId} at the annotation or the
     * adapter must call {@code registerMixinOwner}. Either way, the error must be loud.
     */
    @Test
    void failsLoudlyWithMultipleModsAndNoModIdOrFqnMapping(@TempDir Path tmp) throws Exception {
        Path implJar = tmp.resolve("impl.jar");
        compileFakeImplJar(implJar);

        try (ModClassLoader a = new ModClassLoader("a", new URL[0], getClass().getClassLoader(), List.of());
             ModClassLoader b = new ModClassLoader("b", new URL[]{implJar.toUri().toURL()}, getClass().getClassLoader(), List.of())) {
            McdpProvider.registerMod("a", a);
            McdpProvider.registerMod("b", b);

            IllegalStateException ex = assertThrows(IllegalStateException.class, FqnOnlyMixin::load);
            assertTrue(ex.getMessage().contains("no ModClassLoader"), ex.getMessage());
        }
    }

    @Test
    void failsWhenMixinClassIsNotAnnotated() {
        class Bare {
            static Logic load() {
                return McdpProvider.loadMixinImpl(Logic.class);
            }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class, Bare::load);
        assertTrue(ex.getMessage().contains("missing @McdpMixin"), ex.getMessage());
    }

    /**
     * Auto-bridge path: a mod jar carries a {@code META-INF/mcdp-mixin-bridges/<mixinFqn>.txt}
     * manifest and the impl class. {@code registerMod} populates the registry without ever
     * touching the mixin class (Sponge forbids that). {@link McdpProvider#resolveAutoBridgeImpl}
     * lazily loads the impl through the {@link ModClassLoader} on first call.
     */
    @Test
    void resolveAutoBridgeImplLoadsImplLazilyThroughModLoader(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("auto.jar");
        compileFakeImplJarWithManifest(jar, "com.example.SomeMixin", "LOGIC_X",
                "de.lhns.mcdp.api.FakeImpl");

        try (ModClassLoader mod = new ModClassLoader(
                "auto-mod",
                new URL[]{jar.toUri().toURL()},
                getClass().getClassLoader(),
                List.of())) {

            McdpProvider.registerMod("auto-mod", mod);

            Object impl = McdpProvider.resolveAutoBridgeImpl("com.example.SomeMixin", "LOGIC_X");
            assertNotNull(impl);
            assertEquals("de.lhns.mcdp.api.FakeImpl", impl.getClass().getName());
            // Loaded through the mod loader, not the test loader.
            assertSame(mod, impl.getClass().getClassLoader());

            // Cached: second call returns the same instance.
            Object impl2 = McdpProvider.resolveAutoBridgeImpl("com.example.SomeMixin", "LOGIC_X");
            assertSame(impl, impl2);
        }
    }

    @Test
    void resolveAutoBridgeImplFailsLoudlyWhenUnregistered() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> McdpProvider.resolveAutoBridgeImpl("com.example.Nope", "LOGIC_None"));
        assertTrue(ex.getMessage().contains("no auto-bridge registered"), ex.getMessage());
    }

    private static void compileFakeImplJarWithManifest(Path jarPath, String mixinFqn,
                                                       String fieldName, String implFqn)
            throws Exception {
        Path workDir = Files.createTempDirectory("mclib-auto-");
        Path src = workDir.resolve("FakeImpl.java");
        Files.writeString(src, """
                package de.lhns.mcdp.api;
                public class FakeImpl implements de.lhns.mcdp.api.McdpProviderTest.Logic {
                    public FakeImpl() {}
                    public int compute() { return 42; }
                }
                """);
        Path classes = workDir.resolve("classes");
        Files.createDirectories(classes);
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        int rc = compiler.run(null, null, null,
                "-d", classes.toString(),
                "-classpath", System.getProperty("java.class.path"),
                src.toString());
        assertEquals(0, rc);
        byte[] cls = Files.readAllBytes(classes.resolve("de/lhns/mcdp/api/FakeImpl.class"));
        String manifest = "bridge=" + implFqn + "Bridge\nimpl=" + implFqn + "\nfield=" + fieldName + "\n";

        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jarPath))) {
            out.putNextEntry(new java.util.jar.JarEntry("de/lhns/mcdp/api/FakeImpl.class"));
            out.write(cls);
            out.closeEntry();
            out.putNextEntry(new java.util.jar.JarEntry(
                    "META-INF/mcdp-mixin-bridges/" + mixinFqn + ".txt"));
            out.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    /**
     * Compile a tiny Java source that implements {@link Logic} and package it into a jar.
     * Uses {@link javax.tools.JavaCompiler} so we don't have to emit class-file bytes by hand.
     */
    private static void compileFakeImplJar(Path jarPath) throws Exception {
        Path workDir = Files.createTempDirectory("mclib-impl-");
        Path src = workDir.resolve("FakeImpl.java");
        Files.writeString(src, """
                package de.lhns.mcdp.api;
                public class FakeImpl implements de.lhns.mcdp.api.McdpProviderTest.Logic {
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

        Path classFile = classes.resolve("de/lhns/mcdp/api/FakeImpl.class");
        byte[] bytes = Files.readAllBytes(classFile);

        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jarPath))) {
            var entry = new java.util.jar.JarEntry("de/lhns/mcdp/api/FakeImpl.class");
            out.putNextEntry(entry);
            out.write(bytes);
            out.closeEntry();
        }
    }
}

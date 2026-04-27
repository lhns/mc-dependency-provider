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
     * Auto-bridge happy path (ADR-0019 format): adapter writes a TOML manifest with one
     * {@code [[bridge]]} entry, calls {@link McdpProvider#registerAutoBridgeManifestToml}, and
     * {@link McdpProvider#resolveAutoBridgeImpl} lazily loads the impl through the per-mod
     * loader on first call.
     */
    @Test
    void registerAutoBridgeManifestTomlSingleEntry(@TempDir Path tmp) throws Exception {
        Path implJar = tmp.resolve("impl.jar");
        compileFakeImplJar(implJar);
        Path tomlFile = writeBridgeToml(tmp, """
                [[bridge]]
                mixin = "com.example.SomeMixin"
                field = "LOGIC_X"
                interface = "de.lhns.mcdp.api.FakeImplBridge"
                impl = "de.lhns.mcdp.api.FakeImpl"
                """);

        try (ModClassLoader mod = new ModClassLoader(
                "auto-mod",
                new URL[]{implJar.toUri().toURL()},
                getClass().getClassLoader(),
                List.of())) {
            McdpProvider.registerMod("auto-mod", mod);
            assertEquals(1, McdpProvider.registerAutoBridgeManifestToml(mod, tomlFile));

            Object impl = McdpProvider.resolveAutoBridgeImpl("com.example.SomeMixin", "LOGIC_X");
            assertNotNull(impl);
            assertEquals("de.lhns.mcdp.api.FakeImpl", impl.getClass().getName());
            assertSame(mod, impl.getClass().getClassLoader());

            // Cached: second call returns the same instance.
            assertSame(impl, McdpProvider.resolveAutoBridgeImpl("com.example.SomeMixin", "LOGIC_X"));
        }
    }

    /**
     * Multiple {@code [[bridge]]} entries register independently. Regression for the
     * mc-fluid-physics report: AbstractBlockStateMixin uses TWO mod-private targets
     * (LOGIC_FluidPhysicsConfig + LOGIC_FluidPhysicsMod) and both must register through one
     * manifest read.
     */
    @Test
    void registerAutoBridgeManifestTomlMultipleEntries(@TempDir Path tmp) throws Exception {
        Path implJar = tmp.resolve("impl.jar");
        compileFakeImplJar(implJar);
        Path tomlFile = writeBridgeToml(tmp, """
                [[bridge]]
                mixin = "com.example.AbsMixin"
                field = "LOGIC_First"
                interface = "ifaceA"
                impl = "de.lhns.mcdp.api.FakeImpl"

                [[bridge]]
                mixin = "com.example.AbsMixin"
                field = "LOGIC_Second"
                interface = "ifaceB"
                impl = "de.lhns.mcdp.api.FakeImpl"
                """);

        try (ModClassLoader mod = new ModClassLoader(
                "multi-mod",
                new URL[]{implJar.toUri().toURL()},
                getClass().getClassLoader(),
                List.of())) {
            McdpProvider.registerMod("multi-mod", mod);
            assertEquals(2, McdpProvider.registerAutoBridgeManifestToml(mod, tomlFile));

            assertNotNull(McdpProvider.resolveAutoBridgeImpl("com.example.AbsMixin", "LOGIC_First"));
            assertNotNull(McdpProvider.resolveAutoBridgeImpl("com.example.AbsMixin", "LOGIC_Second"));
        }
    }

    /**
     * Null path → 0 entries, no throw (legitimate "mod has no mixins, no manifest emitted"
     * signal — adapter logs the case separately).
     */
    @Test
    void registerAutoBridgeManifestTomlNoopForNullPath(@TempDir Path tmp) throws Exception {
        Path implJar = tmp.resolve("impl.jar");
        compileFakeImplJar(implJar);
        try (ModClassLoader mod = new ModClassLoader(
                "no-file",
                new URL[]{implJar.toUri().toURL()},
                getClass().getClassLoader(),
                List.of())) {
            assertEquals(0, McdpProvider.registerAutoBridgeManifestToml(mod, null));
        }
    }

    /**
     * Non-null path that doesn't resolve to a readable file → loud throw with diagnostic
     * message. The previous {@code Files.isRegularFile} silent-skip masked NeoForge UnionPath
     * speculative-resolution bugs; this contract surfaces them at boot.
     */
    @Test
    void registerAutoBridgeManifestTomlThrowsForUnreadablePath(@TempDir Path tmp) throws Exception {
        Path implJar = tmp.resolve("impl.jar");
        compileFakeImplJar(implJar);
        try (ModClassLoader mod = new ModClassLoader(
                "ghost", new URL[]{implJar.toUri().toURL()},
                getClass().getClassLoader(), List.of())) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> McdpProvider.registerAutoBridgeManifestToml(
                            mod, tmp.resolve("does-not-exist.toml")));
            assertTrue(ex.getMessage().contains("failed to read auto-bridge manifest"),
                    ex.getMessage());
        }
    }

    /** Incomplete entry (missing required key) → loud throw. */
    @Test
    void registerAutoBridgeManifestTomlThrowsOnIncompleteEntry(@TempDir Path tmp) throws Exception {
        Path implJar = tmp.resolve("impl.jar");
        compileFakeImplJar(implJar);
        Path tomlFile = writeBridgeToml(tmp, """
                [[bridge]]
                mixin = "com.example.M"
                field = "LOGIC_X"
                """); // missing impl

        try (ModClassLoader mod = new ModClassLoader(
                "bad", new URL[]{implJar.toUri().toURL()},
                getClass().getClassLoader(), List.of())) {
            McdpProvider.registerMod("bad", mod);
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> McdpProvider.registerAutoBridgeManifestToml(mod, tomlFile));
            assertTrue(ex.getMessage().contains("incomplete"), ex.getMessage());
        }
    }

    private static Path writeBridgeToml(Path tmp, String content) throws Exception {
        Path p = tmp.resolve("META-INF/mcdp-mixin-bridges.toml");
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    @Test
    void resolveAutoBridgeImplFailsLoudlyWhenUnregistered() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> McdpProvider.resolveAutoBridgeImpl("com.example.Nope", "LOGIC_None"));
        assertTrue(ex.getMessage().contains("no auto-bridge registered"), ex.getMessage());
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

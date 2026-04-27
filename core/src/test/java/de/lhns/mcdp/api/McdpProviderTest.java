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

    /**
     * Auto-bridge manifest dir scan: when the per-mod {@link ModClassLoader}'s URLs don't span
     * the resources content root (NeoForge dev: only one Path lands on the loader, the manifest
     * dir is in a sibling), the platform adapter calls
     * {@link McdpProvider#registerAutoBridgeManifests} with the explicit dir path and impls still
     * resolve correctly.
     */
    @Test
    void registerAutoBridgeManifestsScansExplicitDirectory(@TempDir Path tmp) throws Exception {
        // Impl jar: just the FakeImpl class; the manifest goes in a separate dir to simulate
        // a content root that the ModClassLoader's URL list doesn't cover.
        Path implJar = tmp.resolve("impl.jar");
        compileFakeImplJar(implJar);
        Path manifestDir = tmp.resolve("resources/META-INF/mcdp-mixin-bridges");
        Files.createDirectories(manifestDir);
        Files.writeString(manifestDir.resolve("com.example.NeoMixin.txt"),
                "bridge=de.lhns.mcdp.api.FakeImplBridge\nimpl=de.lhns.mcdp.api.FakeImpl\nfield=LOGIC_Y\n");

        try (ModClassLoader mod = new ModClassLoader(
                "neo-mod",
                new URL[]{implJar.toUri().toURL()}, // manifest dir intentionally NOT on the loader
                getClass().getClassLoader(),
                List.of())) {

            McdpProvider.registerMod("neo-mod", mod);
            // Pre-condition: the URL-only registration finds nothing because the manifest isn't on
            // any URL of the loader.
            IllegalStateException pre = assertThrows(IllegalStateException.class,
                    () -> McdpProvider.resolveAutoBridgeImpl("com.example.NeoMixin", "LOGIC_Y"));
            assertTrue(pre.getMessage().contains("no auto-bridge registered"), pre.getMessage());

            // Explicit-dir scan picks it up.
            int n = McdpProvider.registerAutoBridgeManifests(mod, manifestDir);
            assertEquals(1, n, "expected 1 entry registered from explicit manifest dir");

            Object impl = McdpProvider.resolveAutoBridgeImpl("com.example.NeoMixin", "LOGIC_Y");
            assertNotNull(impl);
            assertEquals("de.lhns.mcdp.api.FakeImpl", impl.getClass().getName());
            assertSame(mod, impl.getClass().getClassLoader());
        }
    }

    /**
     * Index-file API: the canonical runtime registration path. Adapter supplies the index file
     * and a resolver that maps FQN → manifest Path; provider iterates the index and asks the
     * resolver for each line, registering every successful lookup. Fixes the dev-mode crash on
     * NeoForge UnionPath where directory-style {@code findResource} returns a speculative or
     * null path.
     */
    @Test
    void registerAutoBridgeManifestsFromIndexHappyPath(@TempDir Path tmp) throws Exception {
        Path implJar = tmp.resolve("impl.jar");
        compileFakeImplJar(implJar);
        Path manifestDir = tmp.resolve("resources/META-INF/mcdp-mixin-bridges");
        Files.createDirectories(manifestDir);
        Path indexFile = tmp.resolve("resources/META-INF/mcdp-mixin-bridges-index.txt");
        Files.writeString(indexFile, "com.example.IxMixin\n");
        Files.writeString(manifestDir.resolve("com.example.IxMixin.txt"),
                "bridge=de.lhns.mcdp.api.FakeImplBridge\nimpl=de.lhns.mcdp.api.FakeImpl\nfield=LOGIC_Z\n");

        try (ModClassLoader mod = new ModClassLoader(
                "ix-mod",
                new URL[]{implJar.toUri().toURL()},
                getClass().getClassLoader(),
                List.of())) {
            McdpProvider.registerMod("ix-mod", mod);
            int n = McdpProvider.registerAutoBridgeManifestsFromIndex(mod, indexFile,
                    fqn -> manifestDir.resolve(fqn + ".txt"));
            assertEquals(1, n);

            Object impl = McdpProvider.resolveAutoBridgeImpl("com.example.IxMixin", "LOGIC_Z");
            assertNotNull(impl);
            assertSame(mod, impl.getClass().getClassLoader());
        }
    }

    /**
     * Resolver returns null OR points at a non-existent file → that line is silently skipped.
     * Verifies the count reflects only successful lookups; the registry stays consistent.
     */
    @Test
    void registerAutoBridgeManifestsFromIndexSkipsMissingEntries(@TempDir Path tmp) throws Exception {
        Path implJar = tmp.resolve("impl.jar");
        compileFakeImplJar(implJar);
        Path manifestDir = tmp.resolve("META-INF/mcdp-mixin-bridges");
        Files.createDirectories(manifestDir);
        Path indexFile = tmp.resolve("META-INF/mcdp-mixin-bridges-index.txt");
        // Two FQNs in the index; only one has a manifest on disk.
        Files.writeString(indexFile, "com.example.Present\ncom.example.Missing\n");
        Files.writeString(manifestDir.resolve("com.example.Present.txt"),
                "bridge=B\nimpl=de.lhns.mcdp.api.FakeImpl\nfield=LOGIC_P\n");

        try (ModClassLoader mod = new ModClassLoader(
                "skip-mod",
                new URL[]{implJar.toUri().toURL()},
                getClass().getClassLoader(),
                List.of())) {
            McdpProvider.registerMod("skip-mod", mod);
            int n = McdpProvider.registerAutoBridgeManifestsFromIndex(mod, indexFile, fqn -> {
                Path p = manifestDir.resolve(fqn + ".txt");
                return Files.exists(p) ? p : null;
            });
            assertEquals(1, n, "only Present should have registered");

            assertNotNull(McdpProvider.resolveAutoBridgeImpl("com.example.Present", "LOGIC_P"));
            assertThrows(IllegalStateException.class,
                    () -> McdpProvider.resolveAutoBridgeImpl("com.example.Missing", "LOGIC_X"));
        }
    }

    @Test
    void registerAutoBridgeManifestsFromIndexNoopForMissingIndex(@TempDir Path tmp) throws Exception {
        Path implJar = tmp.resolve("impl.jar");
        compileFakeImplJar(implJar);
        try (ModClassLoader mod = new ModClassLoader(
                "no-ix",
                new URL[]{implJar.toUri().toURL()},
                getClass().getClassLoader(),
                List.of())) {
            assertEquals(0, McdpProvider.registerAutoBridgeManifestsFromIndex(
                    mod, tmp.resolve("does-not-exist.txt"), fqn -> null));
        }
    }

    @Test
    void registerAutoBridgeManifestsIsNoopForMissingOrEmptyDir(@TempDir Path tmp) throws Exception {
        Path implJar = tmp.resolve("impl.jar");
        compileFakeImplJar(implJar);
        try (ModClassLoader mod = new ModClassLoader(
                "empty-mod",
                new URL[]{implJar.toUri().toURL()},
                getClass().getClassLoader(),
                List.of())) {
            // Non-existent path → 0, no throw.
            assertEquals(0, McdpProvider.registerAutoBridgeManifests(mod, tmp.resolve("nope")));
            // Empty dir → 0.
            Path empty = Files.createDirectories(tmp.resolve("empty/META-INF/mcdp-mixin-bridges"));
            assertEquals(0, McdpProvider.registerAutoBridgeManifests(mod, empty));
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

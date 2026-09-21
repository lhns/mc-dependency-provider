package de.lhns.mcdp.api;

import de.lhns.mcdp.core.MixinConfigScanner;
import de.lhns.mcdp.core.ModClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The error branches of {@link McdpProvider}'s auto-bridge path, plus the content-addressed
 * manifest/mixin-config overloads that only the FML-10 bands (1.21.11, 26) call.
 *
 * <p>Every one of these was a reachable {@code throw} with no test hitting it. The auto-bridge
 * ones share a failure shape: an impl the codegen promised but the jar does not contain, which
 * builds green and dies in a mixin {@code <clinit>} during boot.
 *
 * <p>Public because the impl classes compiled into the test jars implement {@link Logic}, a
 * nested type of this class, from another package.
 */
public class McdpProviderErrorPathsTest {

    /** Stand-in for a mod's bridge interface; lives on the parent (shared-packages) loader. */
    public interface Logic {
        int compute();
    }

    private static final String MIXIN = "com.example.SomeMixin";
    private static final String FIELD = "LOGIC_X";

    @BeforeEach
    void reset() { McdpProvider.resetForTests(); }

    @AfterEach
    void cleanup() { McdpProvider.resetForTests(); }

    // ------------------------------------------------------------------
    // registerAutoBridgeManifestToml — I/O and parse failures
    // ------------------------------------------------------------------

    /**
     * A manifest that exists and is a regular file but cannot be decoded (truncated UTF-8
     * sequence — a partial write or a corrupted jar entry) must fail loudly. Swallowing the
     * {@link java.io.IOException} and returning 0 would produce a mod with zero bridges and no
     * complaint until the first mixin {@code <clinit>} reports "no auto-bridge registered",
     * pointing at codegen rather than at the broken file.
     */
    @Test
    void throwsWhenManifestCannotBeRead(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("mcdp-bridges.toml");
        Files.write(toml, new byte[]{(byte) 0xC3}); // lead byte with no continuation byte

        try (ModClassLoader mod = emptyLoader("io-mod")) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> McdpProvider.registerAutoBridgeManifestToml(mod, toml));
            assertTrue(ex.getMessage().contains("failed to read auto-bridge manifest"), ex.getMessage());
            assertTrue(ex.getMessage().contains(toml.toString()), ex.getMessage());
            assertInstanceOf(java.io.IOException.class, ex.getCause());
        }
    }

    /** Malformed TOML in an on-disk manifest surfaces as a parse failure naming the file. */
    @Test
    void throwsWhenManifestIsMalformedToml(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("mcdp-bridges.toml");
        Files.writeString(toml, "[[bridge]]\nthis line has no equals sign\n");

        try (ModClassLoader mod = emptyLoader("parse-mod")) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> McdpProvider.registerAutoBridgeManifestToml(mod, toml));
            assertTrue(ex.getMessage().contains("failed to parse auto-bridge manifest"), ex.getMessage());
            assertTrue(ex.getMessage().contains(toml.toString()), ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // resolveAutoBridgeImpl — instantiation failures
    // ------------------------------------------------------------------

    /**
     * The single most likely codegen bug: the manifest names an impl the jar does not contain.
     * The message must name the impl FQN and the mixin field, because that pair is what tells a
     * mod author which {@code @McdpBridge} did not get its class emitted.
     */
    @Test
    void throwsWhenImplIsMissingFromTheJar(@TempDir Path tmp) throws Exception {
        Path jar = implJar(tmp, Map.of("GoodImpl", GOOD_IMPL));

        try (ModClassLoader mod = loaderFor("missing-mod", jar)) {
            McdpProvider.registerMod("missing-mod", mod);
            registerBridge(mod, "com.example.bridge.NotEmittedImpl");

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> McdpProvider.resolveAutoBridgeImpl(MIXIN, FIELD));
            assertTrue(ex.getMessage().contains("failed to instantiate auto-bridge impl"), ex.getMessage());
            assertTrue(ex.getMessage().contains("com.example.bridge.NotEmittedImpl"), ex.getMessage());
            assertTrue(ex.getMessage().contains(MIXIN + "." + FIELD), ex.getMessage());
            assertInstanceOf(ClassNotFoundException.class, ex.getCause());
        }
    }

    /** Impl present but with no no-arg constructor — hand-written impls hit this. */
    @Test
    void throwsWhenImplHasNoNoArgConstructor(@TempDir Path tmp) throws Exception {
        Path jar = implJar(tmp, Map.of("NoNoArgImpl", NO_NO_ARG_IMPL));

        try (ModClassLoader mod = loaderFor("ctor-mod", jar)) {
            McdpProvider.registerMod("ctor-mod", mod);
            registerBridge(mod, "com.example.bridge.NoNoArgImpl");

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> McdpProvider.resolveAutoBridgeImpl(MIXIN, FIELD));
            assertTrue(ex.getMessage().contains("failed to instantiate auto-bridge impl"), ex.getMessage());
            assertInstanceOf(NoSuchMethodException.class, ex.getCause());
        }
    }

    /**
     * Impl constructor throws. The original exception must stay reachable through the cause
     * chain — it is the only thing that says what actually went wrong inside the mod.
     */
    @Test
    void throwsWhenImplConstructorThrows(@TempDir Path tmp) throws Exception {
        Path jar = implJar(tmp, Map.of("BoomImpl", BOOM_IMPL));

        try (ModClassLoader mod = loaderFor("boom-mod", jar)) {
            McdpProvider.registerMod("boom-mod", mod);
            registerBridge(mod, "com.example.bridge.BoomImpl");

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> McdpProvider.resolveAutoBridgeImpl(MIXIN, FIELD));
            assertTrue(ex.getMessage().contains("failed to instantiate auto-bridge impl"), ex.getMessage());
            assertInstanceOf(java.lang.reflect.InvocationTargetException.class, ex.getCause());
            assertEquals("ctor boom", ex.getCause().getCause().getMessage());
        }
    }

    /** A failed resolve must not be cached as a success — the next call re-runs and re-reports. */
    @Test
    void failedResolveIsNotCached(@TempDir Path tmp) throws Exception {
        Path jar = implJar(tmp, Map.of("GoodImpl", GOOD_IMPL));
        try (ModClassLoader mod = loaderFor("recheck-mod", jar)) {
            McdpProvider.registerMod("recheck-mod", mod);
            registerBridge(mod, "com.example.bridge.NotEmittedImpl");

            assertThrows(IllegalStateException.class, () -> McdpProvider.resolveAutoBridgeImpl(MIXIN, FIELD));
            assertThrows(IllegalStateException.class, () -> McdpProvider.resolveAutoBridgeImpl(MIXIN, FIELD));
        }
    }

    // ------------------------------------------------------------------
    // loadMixinImpl — wrong impl type
    // ------------------------------------------------------------------

    /** Mixin whose {@code impl} names a class that does not implement the requested interface. */
    @McdpMixin(impl = "com.example.bridge.UnrelatedImpl", modId = "wrong-type-mod")
    public static class WrongTypeMixin {
        static Logic load() {
            return McdpProvider.loadMixinImpl(Logic.class);
        }
    }

    /**
     * {@code @McdpMixin(impl = …)} pointing at a class that loads fine but implements something
     * else. Without the {@code isAssignableFrom} guard this reaches {@code iface.cast} and
     * reports a {@link ClassCastException} with no hint of which annotation is wrong.
     */
    @Test
    void loadMixinImplThrowsWhenImplDoesNotImplementInterface(@TempDir Path tmp) throws Exception {
        Path jar = implJar(tmp, Map.of("UnrelatedImpl", UNRELATED_IMPL));

        try (ModClassLoader mod = loaderFor("wrong-type-mod", jar)) {
            McdpProvider.registerMod("wrong-type-mod", mod);

            IllegalStateException ex = assertThrows(IllegalStateException.class, WrongTypeMixin::load);
            assertTrue(ex.getMessage().contains("does not implement"), ex.getMessage());
            assertTrue(ex.getMessage().contains("com.example.bridge.UnrelatedImpl"), ex.getMessage());
            assertTrue(ex.getMessage().contains(Logic.class.getName()), ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // registerMixinOwner — unknown modId, and its escape from the best-effort scan
    // ------------------------------------------------------------------

    /** Direct call with a modId nobody registered. */
    @Test
    void registerMixinOwnerThrowsForUnknownModId() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> McdpProvider.registerMixinOwner("com.example.mixin.FooMixin", "never-registered"));
        assertTrue(ex.getMessage().contains("no ModClassLoader registered for modId"), ex.getMessage());
        assertTrue(ex.getMessage().contains("never-registered"), ex.getMessage());
    }

    /**
     * An unknown modId must not escape the scan. ADR-0008 path 1 — the annotation's own
     * {@code modId} — is the guarantee; this scan is defense-in-depth, and the method documents
     * itself as best-effort. It previously caught only {@link IllegalArgumentException}, so
     * {@code registerMixinOwner}'s "no ModClassLoader registered" propagated out and aborted
     * adapter boot. A config naming a sibling mod's mixins does that with no ordering bug at all.
     */
    @Test
    void registerMixinOwnersFromConfigContentsSwallowsUnknownModId() {
        String config = "{\"package\":\"com.example.mixin\",\"mixins\":[\"FooMixin\"]}";

        assertEquals(List.of(), MixinConfigScanner.registerMixinOwnersFromConfigContents(
                "unregistered", List.of(config)));
    }

    /**
     * And it must not abort mid-list: a config for an unregistered mod cannot cost the configs
     * after it their registration. That blast radius is what made the old escape worth fixing.
     */
    @Test
    void unknownModIdDoesNotStopLaterConfigs(@TempDir Path tmp) throws Exception {
        Path jar = implJar(tmp, Map.of("GoodImpl", GOOD_IMPL));
        try (ModClassLoader mod = loaderFor("known", jar)) {
            McdpProvider.registerMod("known", mod);

            String forUnknown = "{\"package\":\"a\",\"mixins\":[\"One\"]}";
            String forKnown = "{\"package\":\"b\",\"mixins\":[\"Two\"]}";

            // First config targets a mod that was never registered; the second must still land.
            assertEquals(List.of(), MixinConfigScanner.registerMixinOwnersFromConfigContents(
                    "unregistered", List.of(forUnknown)));
            assertEquals(List.of("b.Two"), MixinConfigScanner.registerMixinOwnersFromConfigContents(
                    "known", List.of(forKnown)));
        }
    }

    // ------------------------------------------------------------------
    // Item 3 — content overloads (FML-10 bands only)
    // ------------------------------------------------------------------

    @Test
    void registerAutoBridgeManifestTomlContentRegistersAndResolves(@TempDir Path tmp) throws Exception {
        Path jar = implJar(tmp, Map.of("GoodImpl", GOOD_IMPL));
        try (ModClassLoader mod = loaderFor("content-mod", jar)) {
            McdpProvider.registerMod("content-mod", mod);

            assertEquals(1, McdpProvider.registerAutoBridgeManifestTomlContent(mod, """
                    [[bridge]]
                    mixin = "com.example.SomeMixin"
                    field = "LOGIC_X"
                    interface = "com.example.bridge.Logic"
                    impl = "com.example.bridge.GoodImpl"
                    """, "jar:content-mod!/META-INF/mcdp-bridges.toml"));

            Object impl = McdpProvider.resolveAutoBridgeImpl(MIXIN, FIELD);
            assertEquals("com.example.bridge.GoodImpl", impl.getClass().getName());
            assertEquals(7, ((Logic) impl).compute());
        }
    }

    /** Two entries from one content read, the same shape the Path overload is tested for. */
    @Test
    void registerAutoBridgeManifestTomlContentRegistersMultipleEntries(@TempDir Path tmp) throws Exception {
        Path jar = implJar(tmp, Map.of("GoodImpl", GOOD_IMPL));
        try (ModClassLoader mod = loaderFor("content-multi", jar)) {
            McdpProvider.registerMod("content-multi", mod);
            assertEquals(2, McdpProvider.registerAutoBridgeManifestTomlContent(mod, """
                    [[bridge]]
                    mixin = "com.example.AbsMixin"
                    field = "LOGIC_First"
                    interface = "ifaceA"
                    impl = "com.example.bridge.GoodImpl"

                    [[bridge]]
                    mixin = "com.example.AbsMixin"
                    field = "LOGIC_Second"
                    interface = "ifaceB"
                    impl = "com.example.bridge.GoodImpl"
                    """, "src"));
        }
    }

    /**
     * {@code null}, empty and whitespace-only content are all the "mod has no bridges" signal.
     * FML 10's {@code JarContents} read hands back an empty string for a zero-length entry, so
     * the blank cases are reachable in production, not just defensive.
     */
    @Test
    void registerAutoBridgeManifestTomlContentNoopsOnBlankInput(@TempDir Path tmp) throws Exception {
        try (ModClassLoader mod = emptyLoader("blank-mod")) {
            assertEquals(0, McdpProvider.registerAutoBridgeManifestTomlContent(mod, null, "src"));
            assertEquals(0, McdpProvider.registerAutoBridgeManifestTomlContent(mod, "", "src"));
            assertEquals(0, McdpProvider.registerAutoBridgeManifestTomlContent(mod, "   \n\t\r\n  ", "src"));
        }
    }

    /** Well-formed TOML with no {@code [[bridge]]} table is 0 entries, not an error. */
    @Test
    void registerAutoBridgeManifestTomlContentNoopsWithoutBridgeTable() throws Exception {
        try (ModClassLoader mod = emptyLoader("no-table")) {
            assertEquals(0, McdpProvider.registerAutoBridgeManifestTomlContent(
                    mod, "lang = \"scala\"\n", "src"));
        }
    }

    /**
     * A UTF-8 BOM ahead of the first {@code [[bridge]]} header. {@code MiniToml} does not strip
     * U+FEFF (Java's {@code String.strip} does not treat it as whitespace), so the header is not
     * recognised and the manifest is rejected. Loud is the right answer here — the alternative is
     * a mod whose bridges all silently vanish — but the message says "malformed" when the real
     * cause is an editor's byte-order mark, so this test pins the behaviour rather than blessing
     * the diagnostic.
     */
    @Test
    void registerAutoBridgeManifestTomlContentRejectsUtf8Bom() throws Exception {
        String withBom = "﻿[[bridge]]\nmixin = \"m\"\nfield = \"f\"\nimpl = \"i\"\n";
        try (ModClassLoader mod = emptyLoader("bom-mod")) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> McdpProvider.registerAutoBridgeManifestTomlContent(mod, withBom, "bommed.toml"));
            assertTrue(ex.getMessage().contains("failed to parse auto-bridge manifest"), ex.getMessage());
            assertTrue(ex.getMessage().contains("bommed.toml"), ex.getMessage());
        }
    }

    /** Malformed TOML through the content overload names the caller-supplied source, not a path. */
    @Test
    void registerAutoBridgeManifestTomlContentRejectsMalformedToml() throws Exception {
        try (ModClassLoader mod = emptyLoader("bad-content")) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> McdpProvider.registerAutoBridgeManifestTomlContent(
                            mod, "[[bridge]\nmixin = \"m\"\n", "jar:bad-content!/x.toml"));
            assertTrue(ex.getMessage().contains("failed to parse auto-bridge manifest"), ex.getMessage());
            assertTrue(ex.getMessage().contains("jar:bad-content!/x.toml"), ex.getMessage());
        }
    }

    /** Incomplete entry through the content overload is loud too, and names the source. */
    @Test
    void registerAutoBridgeManifestTomlContentRejectsIncompleteEntry() throws Exception {
        try (ModClassLoader mod = emptyLoader("incomplete-content")) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> McdpProvider.registerAutoBridgeManifestTomlContent(
                            mod, "[[bridge]]\nmixin = \"m\"\nfield = \"f\"\n", "inline"));
            assertTrue(ex.getMessage().contains("incomplete"), ex.getMessage());
            assertTrue(ex.getMessage().contains("inline"), ex.getMessage());
        }
    }

    // --- MixinConfigScanner.registerMixinOwnersFromConfigContents ---

    @Test
    void registerMixinOwnersFromConfigContentsRegistersFqns() throws Exception {
        try (ModClassLoader mod = emptyLoader("scan-mod")) {
            McdpProvider.registerMod("scan-mod", mod);
            List<String> registered = MixinConfigScanner.registerMixinOwnersFromConfigContents(
                    "scan-mod", List.of(
                            "{\"package\":\"com.example.mixin\",\"mixins\":[\"FooMixin\"],\"client\":[\"BarClient\"]}",
                            "{\"package\":\"com.example.other\",\"server\":[\"BazServer\"]}"));

            assertEquals(List.of("com.example.mixin.FooMixin",
                    "com.example.mixin.BarClient",
                    "com.example.other.BazServer"), registered);
            assertSame(mod, McdpProvider.loaderFor("scan-mod"));
        }
    }

    /**
     * Null, empty and whitespace-only config entries are skipped without touching the parser —
     * a {@code JarContents} read of an empty or absent mixin config produces exactly these.
     */
    @Test
    void registerMixinOwnersFromConfigContentsSkipsBlankEntries() throws Exception {
        try (ModClassLoader mod = emptyLoader("blank-scan")) {
            McdpProvider.registerMod("blank-scan", mod);
            List<String> withNull = new ArrayList<>();
            Collections.addAll(withNull, null, "", "   \n\t ");

            assertEquals(List.of(),
                    MixinConfigScanner.registerMixinOwnersFromConfigContents("blank-scan", withNull));
        }
    }

    /**
     * Malformed and BOM-prefixed configs stay on the best-effort path — silently skipped, with
     * the well-formed siblings in the same batch still registered. A BOM reaches {@code MiniJson}
     * as an unexpected leading character; it must surface as the {@link IllegalArgumentException}
     * the scanner catches, not as an unchecked parser fault that aborts boot.
     */
    @Test
    void registerMixinOwnersFromConfigContentsSkipsMalformedAndBomButKeepsGoing() throws Exception {
        try (ModClassLoader mod = emptyLoader("mixed-scan")) {
            McdpProvider.registerMod("mixed-scan", mod);

            List<String> registered = MixinConfigScanner.registerMixinOwnersFromConfigContents(
                    "mixed-scan", List.of(
                            "this is not json",
                            "﻿{\"package\":\"com.example.bom\",\"mixins\":[\"BomMixin\"]}",
                            "{\"package\":\"com.example.mixin\",\"mixins\":[\"FooMixin\"]",   // truncated
                            "{\"package\":\"com.example.ok\",\"mixins\":[\"OkMixin\"]}"));

            assertEquals(List.of("com.example.ok.OkMixin"), registered);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void registerBridge(ModClassLoader mod, String implFqn) {
        assertEquals(1, McdpProvider.registerAutoBridgeManifestTomlContent(mod, """
                [[bridge]]
                mixin = "%s"
                field = "%s"
                interface = "com.example.bridge.Logic"
                impl = "%s"
                """.formatted(MIXIN, FIELD, implFqn), "inline"));
    }

    private ModClassLoader emptyLoader(String modId) {
        return new ModClassLoader(modId, new URL[0], getClass().getClassLoader(), List.of());
    }

    private ModClassLoader loaderFor(String modId, Path jar) throws Exception {
        return new ModClassLoader(modId, new URL[]{jar.toUri().toURL()},
                getClass().getClassLoader(), List.of());
    }

    private static final String LOGIC = "de.lhns.mcdp.api.McdpProviderErrorPathsTest.Logic";

    private static final String GOOD_IMPL = """
            package com.example.bridge;
            public class GoodImpl implements %s {
                public GoodImpl() {}
                public int compute() { return 7; }
            }
            """.formatted(LOGIC);

    private static final String NO_NO_ARG_IMPL = """
            package com.example.bridge;
            public class NoNoArgImpl implements %s {
                private final int v;
                public NoNoArgImpl(int v) { this.v = v; }
                public int compute() { return v; }
            }
            """.formatted(LOGIC);

    private static final String BOOM_IMPL = """
            package com.example.bridge;
            public class BoomImpl implements %s {
                public BoomImpl() { throw new IllegalStateException("ctor boom"); }
                public int compute() { return 0; }
            }
            """.formatted(LOGIC);

    /** Loads and instantiates fine, just isn't a {@link Logic}. */
    private static final String UNRELATED_IMPL = """
            package com.example.bridge;
            public class UnrelatedImpl {
                public UnrelatedImpl() {}
            }
            """;

    /**
     * Compile the given {@code simpleName -> source} pairs with the real javac and pack every
     * resulting class file into one jar. Mirrors {@code McdpProviderTest.compileFakeImplJar}, but
     * emits into {@code com.example.bridge} — a mod-private package, so {@link ModClassLoader}
     * resolves it child-first exactly as it would a real bridge impl.
     */
    private static Path implJar(Path tmp, Map<String, String> sources) throws Exception {
        Path work = Files.createTempDirectory("mcdp-errpaths-");
        Path srcDir = Files.createDirectories(work.resolve("src/com/example/bridge"));
        List<String> args = new ArrayList<>(List.of(
                "-d", Files.createDirectories(work.resolve("classes")).toString(),
                "-classpath", System.getProperty("java.class.path")));
        for (Map.Entry<String, String> e : new LinkedHashMap<>(sources).entrySet()) {
            Path src = srcDir.resolve(e.getKey() + ".java");
            Files.writeString(src, e.getValue(), StandardCharsets.UTF_8);
            args.add(src.toString());
        }

        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK required to run this test");
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), "javac failed");

        Path classes = work.resolve("classes");
        Path jar = tmp.resolve("impls-" + Math.abs(sources.keySet().hashCode()) + ".jar");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar));
             var walk = Files.walk(classes)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                out.putNextEntry(new java.util.jar.JarEntry(
                        classes.relativize(p).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(p));
                out.closeEntry();
            }
        }
        return jar;
    }
}

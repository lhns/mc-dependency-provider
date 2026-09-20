package de.lhns.mcdp.gradle.bridges;

import de.lhns.mcdp.api.McdpProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0021 lambda bridging, executed rather than merely parsed.
 *
 * <p>Every other lambda test in this package inspects emitted bytes with ASM. That is not
 * enough: a call site with a wrong bootstrap descriptor, or with {@code samMethodType} and
 * {@code instantiatedMethodType} conflated, parses perfectly and still throws
 * {@code BootstrapMethodError}/{@code AbstractMethodError} the first time the JVM links it.
 * This test therefore <em>defines</em> the rewritten mixin plus every generated bridge
 * interface and impl in a real {@link ClassLoader}, invokes the rewritten methods, and calls
 * the SAM instances that come back.</p>
 *
 * <p>The fixture is compiled with the real {@code javac} at test time (rather than hand-built
 * with ASM) on purpose: hand-written fixtures can only ever encode what we <em>believe</em>
 * javac emits, and the historical lambda bugs were exactly a mismatch between that belief and
 * reality. Covered shapes: non-capturing lambda, capturing lambda, generic SAM
 * ({@code Supplier<String>}, {@code Function<String,Integer>}), method reference,
 * serializable lambda ({@code altMetafactory}), a lambda that follows a branch (so the method
 * carries {@code StackMapTable} frames ahead of the indy), and an instance-capturing lambda
 * (which must be rejected, not mis-emitted).</p>
 */
class LambdaBridgeRuntimeTest {

    private static final String BRIDGE_PKG = "com.example.mod.mcdp_bridges";
    private static final String MIXIN_FQN = "com.example.mod.MixinFoo";

    private final BridgePolicy policy = new BridgePolicy(List.of(), BRIDGE_PKG);

    /**
     * The fixture. Every lambda body sticks to platform types so the scan produces lambda
     * sites and nothing else — this test is about the indy rewrite, not about member bridging
     * (which {@link BridgeRewriterEndToEndTest} already executes). {@code concat} is used
     * instead of {@code +} to keep {@code StringConcatFactory} indys out of the picture.
     */
    private static final String FIXTURE_SOURCE = """
            package com.example.mod;

            import java.io.Serializable;
            import java.util.function.Function;
            import java.util.function.Supplier;

            public class MixinFoo {

                public int field = 7;

                /** Non-capturing lambda, generic SAM: samMethodType ()Object vs instantiated ()String. */
                public static Supplier<String> plain() {
                    return () -> "plain";
                }

                /** Capturing lambda — the capture is the indy's only argument. */
                public static Supplier<String> capturing(String s) {
                    return () -> s.concat("!");
                }

                /** Generic SAM in both directions: (Object)Object erased, (String)Integer instantiated. */
                public static Function<String, Integer> lengthFn() {
                    return x -> x.length();
                }

                public static String helper() {
                    return "ref";
                }

                /** Method reference to a real (non-synthetic) static method on this same class. */
                public static Supplier<String> methodRef() {
                    return MixinFoo::helper;
                }

                /**
                 * A branch ahead of the lambda. javac emits a StackMapTable frame at the join,
                 * which shifts every positional instruction index after it.
                 */
                public static Supplier<String> afterBranch(boolean b) {
                    String v = "x";
                    if (b) {
                        v = v.concat("y");
                    }
                    final String f = v;
                    return () -> f.concat("!");
                }

                /** Serializable lambda — javac routes this through altMetafactory with extra bsm args. */
                public static Supplier<String> serializableLambda() {
                    return (Supplier<String> & Serializable) () -> "ser";
                }

                /** Captures {@code this}: javac emits an INSTANCE synthetic, not a static one. */
                public Supplier<String> instanceLambda() {
                    return () -> "i".concat(Integer.toString(this.field));
                }
            }
            """;

    @AfterEach
    void clearProviderStub() {
        McdpProvider.clearForTest();
    }

    // ---------------------------------------------------------------- C1 / C2

    @Test
    void everyGeneratedWrapperLinksAndProducesAWorkingSam(@TempDir Path tmp) throws Exception {
        Pipeline p = run(tmp);

        // plain(): non-capturing, generic SAM.
        assertEquals("plain", ((Supplier<?>) p.invokeStatic("plain")).get());
        // capturing(String): one capture flows through the bridge's make(String).
        assertEquals("ab!", ((Supplier<?>) p.invokeStatic("capturing", new Class<?>[]{String.class}, "ab")).get());
        // Generic SAM with a specific instantiated type in both parameter and return position.
        @SuppressWarnings("unchecked")
        Function<String, Integer> fn = (Function<String, Integer>) p.invokeStatic("lengthFn");
        assertEquals(5, fn.apply("hello"));
        // Method reference to a static method on the container.
        assertEquals("ref", ((Supplier<?>) p.invokeStatic("methodRef")).get());
    }

    @Test
    void serializableLambdaKeepsAltMetafactoryAndItsExtraBsmArgs(@TempDir Path tmp) throws Exception {
        Pipeline p = run(tmp);
        Object sam = p.invokeStatic("serializableLambda");
        assertEquals("ser", ((Supplier<?>) sam).get());
        assertTrue(sam instanceof Serializable,
                "altMetafactory's FLAG_SERIALIZABLE must survive the replay — the generated "
                        + "SAM instance is expected to implement Serializable");
    }

    @Test
    void bootstrapHandleIsTheRealMetafactorySignature(@TempDir Path tmp) throws Exception {
        // A MethodHandle constant resolves by exact name+descriptor. metafactory is the fixed
        // 6-arg form; altMetafactory is the varargs form. Emitting one descriptor for the other
        // name is an unlinkable call site, so assert the pairing structurally too.
        Pipeline p = run(tmp);
        for (LambdaWrapperEmitter.Artifacts art : p.arts.values()) {
            ClassNode impl = parse(art.bridgeImplBytes);
            MethodNode make = findMethod(impl, art.makeName, art.makeDescriptor);
            assertNotNull(make, "impl must declare " + art.makeName + art.makeDescriptor);
            boolean sawIndy = false;
            for (AbstractInsnNode insn : make.instructions) {
                if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
                    sawIndy = true;
                    String name = indy.bsm.getName();
                    String desc = indy.bsm.getDesc();
                    if ("metafactory".equals(name)) {
                        assertEquals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                        + "Ljava/lang/invoke/CallSite;",
                                desc, "metafactory is the fixed 6-arg form");
                    } else {
                        assertEquals("altMetafactory", name);
                        assertEquals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                        + "Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                                        + "Ljava/lang/invoke/CallSite;",
                                desc, "altMetafactory is the varargs form");
                    }
                    // samMethodType (erased) and instantiatedMethodType (specific) are distinct
                    // values carried from the original site — never the same object twice.
                    assertEquals(org.objectweb.asm.Type.class, indy.bsmArgs[0].getClass());
                    assertEquals(org.objectweb.asm.Type.class, indy.bsmArgs[2].getClass());
                }
            }
            assertTrue(sawIndy, "make body must contain an INVOKEDYNAMIC");
        }
    }

    @Test
    void genericSamKeepsErasedAndInstantiatedTypesApart(@TempDir Path tmp) throws Exception {
        Pipeline p = run(tmp);
        LambdaWrapperEmitter.Artifacts art = p.artFor("lengthFn");
        ClassNode impl = parse(art.bridgeImplBytes);
        MethodNode make = findMethod(impl, art.makeName, art.makeDescriptor);
        org.objectweb.asm.tree.InvokeDynamicInsnNode indy = null;
        for (AbstractInsnNode insn : make.instructions) {
            if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode i) indy = i;
        }
        assertNotNull(indy);
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", indy.bsmArgs[0].toString(),
                "samMethodType must stay erased");
        assertEquals("(Ljava/lang/String;)Ljava/lang/Integer;", indy.bsmArgs[2].toString(),
                "instantiatedMethodType must stay specific");
    }

    // ---------------------------------------------------------------------- C3

    @Test
    void lambdaAfterABranchIsActuallyRewritten(@TempDir Path tmp) throws Exception {
        // The scanner reads with SKIP_FRAMES and the rewriter without; a positional instruction
        // index therefore used to drift by the number of FrameNodes ahead of the indy, and the
        // site silently failed to match. Assert structurally that no INVOKEDYNAMIC survives in
        // afterBranch and that the bridge is dispatched instead.
        Pipeline p = run(tmp);
        ClassNode rewritten = parse(p.rewrittenMixin);
        MethodNode m = findMethodByName(rewritten, "afterBranch");
        assertNotNull(m);
        for (AbstractInsnNode insn : m.instructions) {
            assertFalse(insn.getOpcode() == Opcodes.INVOKEDYNAMIC,
                    "afterBranch still contains an INVOKEDYNAMIC — the lambda site was not rewritten");
        }
        LambdaWrapperEmitter.Artifacts art = p.artFor("afterBranch");
        assertTrue(referencesBridge(m, art.bridgeIfaceInternal),
                "afterBranch must dispatch through " + art.bridgeIfaceInternal);

        // ...and it still behaves.
        assertEquals("xy!", ((Supplier<?>) p.invokeStatic("afterBranch",
                new Class<?>[]{boolean.class}, true)).get());
        assertEquals("x!", ((Supplier<?>) p.invokeStatic("afterBranch",
                new Class<?>[]{boolean.class}, false)).get());
    }

    @Test
    void everyAcceptedSiteLosesItsIndy(@TempDir Path tmp) throws Exception {
        Pipeline p = run(tmp);
        ClassNode rewritten = parse(p.rewrittenMixin);
        for (LambdaSite site : p.result.lambdaSites()) {
            MethodNode m = findMethodById(rewritten, site.ownerMethodId());
            assertNotNull(m, "missing " + site.ownerMethodId());
            LambdaWrapperEmitter.Artifacts art = p.arts.get(site.siteIndex());
            assertTrue(referencesBridge(m, art.bridgeIfaceInternal),
                    site.ownerMethodId() + " should dispatch through " + art.bridgeIfaceInternal);
        }
    }

    // ---------------------------------------------------------------------- C4

    @Test
    void instanceCapturingLambdaIsRejectedWholesale(@TempDir Path tmp) throws Exception {
        Pipeline p = run(tmp);

        // No site recorded for instanceLambda...
        for (LambdaSite site : p.result.lambdaSites()) {
            assertFalse(site.ownerMethodId().startsWith("instanceLambda"),
                    "an instance-synthetic lambda must not produce a site: " + site);
        }
        // ...a warning that names mixin, method and lambda...
        String warning = p.result.warnings().stream()
                .filter(w -> w.contains("instanceLambda"))
                .findFirst().orElse(null);
        assertNotNull(warning, "expected a rejection warning, got: " + p.result.warnings());
        assertTrue(warning.contains(MIXIN_FQN), "warning must name the mixin: " + warning);
        assertTrue(warning.contains("lambda$instanceLambda$"),
                "warning must name the lambda implementation method: " + warning);

        // ...no LAMBDA_ field, no <clinit> entry and no manifest-bound artifacts for it, and the
        // untouched indy is still in place (so the class stays self-consistent rather than
        // half-rewritten).
        ClassNode rewritten = parse(p.rewrittenMixin);
        MethodNode m = findMethodByName(rewritten, "instanceLambda");
        boolean hasIndy = false;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn.getOpcode() == Opcodes.INVOKEDYNAMIC) hasIndy = true;
        }
        assertTrue(hasIndy, "the rejected lambda must be left exactly as compiled");

        int lambdaFields = 0;
        for (FieldNode f : rewritten.fields) {
            if (f.name.startsWith("LAMBDA_")) lambdaFields++;
        }
        assertEquals(p.result.lambdaSites().size(), lambdaFields,
                "one LAMBDA_ field per accepted site and not one more");
    }

    @Test
    void rejectedLambdaStillRunsOnTheOriginalClass(@TempDir Path tmp) throws Exception {
        Pipeline p = run(tmp);
        Object instance = p.mixinClass.getDeclaredConstructor().newInstance();
        Method m = p.mixinClass.getDeclaredMethod("instanceLambda");
        assertEquals("i7", ((Supplier<?>) m.invoke(instance)).get());
    }

    // ----------------------------------------------------------------- H3 names

    @Test
    void wrapperNamesAreUniquePerSite(@TempDir Path tmp) throws Exception {
        Pipeline p = run(tmp);
        List<String> names = new ArrayList<>();
        for (LambdaWrapperEmitter.Artifacts art : p.arts.values()) {
            assertFalse(names.contains(art.bridgeIfaceInternal), "duplicate wrapper name");
            names.add(art.bridgeIfaceInternal);
        }
        assertEquals(p.result.lambdaSites().size(), names.size());
    }

    // ------------------------------------------------------------------ plumbing

    /** Scan → emit wrappers → rewrite → define everything in one loader. */
    private Pipeline run(Path tmp) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "needs a JDK (javac) on the test runtime");

        byte[] mixinBytes = compileFixture(compiler, tmp);

        BridgeScanResult result = new BridgeScanner(policy).scan(mixinBytes);
        assertEquals(BridgeScanResult.Status.REWRITABLE, result.status(),
                "fixture should be rewritable: " + result.errors());
        assertFalse(result.lambdaSites().isEmpty(), "expected lambda sites");

        ClassNode container = parse(mixinBytes);
        LambdaWrapperEmitter emitter =
                new LambdaWrapperEmitter(BRIDGE_PKG, getClass().getClassLoader());

        Map<Integer, LambdaWrapperEmitter.Artifacts> arts = new LinkedHashMap<>();
        Map<String, byte[]> classes = new HashMap<>();
        for (LambdaSite site : result.lambdaSites()) {
            MethodNode synthetic = findMethod(container,
                    site.implMethod().getName(), site.implMethod().getDesc());
            assertNotNull(synthetic, "missing synthetic for " + site);
            LambdaWrapperEmitter.Artifacts art = emitter.emit(container, site, synthetic);
            arts.put(site.siteIndex(), art);
            classes.put(BridgePolicy.toDotted(art.bridgeIfaceInternal), art.bridgeIfaceBytes);
            classes.put(BridgePolicy.toDotted(art.bridgeImplInternal), art.bridgeImplBytes);
        }

        byte[] rewritten = new BridgeRewriter(policy, BRIDGE_PKG, getClass().getClassLoader())
                .rewrite(mixinBytes, result.targets(), result.lambdaSites(), arts);
        classes.put(MIXIN_FQN, rewritten);

        InMemLoader loader = new InMemLoader(classes, getClass().getClassLoader());
        for (LambdaWrapperEmitter.Artifacts art : arts.values()) {
            Class<?> implClass = loader.loadClass(BridgePolicy.toDotted(art.bridgeImplInternal));
            McdpProvider.registerForTest(MIXIN_FQN, art.logicFieldName,
                    implClass.getDeclaredConstructor().newInstance());
        }
        Class<?> mixinClass = loader.loadClass(MIXIN_FQN);
        return new Pipeline(result, arts, rewritten, mixinClass);
    }

    private static byte[] compileFixture(JavaCompiler compiler, Path tmp) throws Exception {
        Path src = tmp.resolve("com/example/mod/MixinFoo.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, FIXTURE_SOURCE);
        Path out = tmp.resolve("classes");
        Files.createDirectories(out);
        int rc = compiler.run(null, null, null,
                "-d", out.toString(), "-g", src.toString());
        assertEquals(0, rc, "fixture failed to compile");
        return Files.readAllBytes(out.resolve("com/example/mod/MixinFoo.class"));
    }

    private static final class Pipeline {
        final BridgeScanResult result;
        final Map<Integer, LambdaWrapperEmitter.Artifacts> arts;
        final byte[] rewrittenMixin;
        final Class<?> mixinClass;

        Pipeline(BridgeScanResult result, Map<Integer, LambdaWrapperEmitter.Artifacts> arts,
                 byte[] rewrittenMixin, Class<?> mixinClass) {
            this.result = result;
            this.arts = arts;
            this.rewrittenMixin = rewrittenMixin;
            this.mixinClass = mixinClass;
        }

        Object invokeStatic(String name) throws Exception {
            return invokeStatic(name, new Class<?>[0]);
        }

        Object invokeStatic(String name, Class<?>[] params, Object... args) throws Exception {
            Method m = mixinClass.getDeclaredMethod(name, params);
            return m.invoke(null, args);
        }

        /** The artifacts of the (single) lambda site declared inside {@code methodName}. */
        LambdaWrapperEmitter.Artifacts artFor(String methodName) {
            for (LambdaSite site : result.lambdaSites()) {
                if (site.ownerMethodId().startsWith(methodName + "(")) {
                    return arts.get(site.siteIndex());
                }
            }
            throw new AssertionError("no lambda site in " + methodName
                    + "; sites = " + result.lambdaSites());
        }
    }

    private static boolean referencesBridge(MethodNode m, String bridgeInternal) {
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode min
                    && min.owner.equals(bridgeInternal)) {
                return true;
            }
        }
        return false;
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return m;
        }
        return null;
    }

    private static MethodNode findMethodByName(ClassNode cn, String name) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name)) return m;
        }
        return null;
    }

    private static MethodNode findMethodById(ClassNode cn, String methodId) {
        for (MethodNode m : cn.methods) {
            if (methodId.equals(m.name + m.desc)) return m;
        }
        return null;
    }

    /** Loader that defines a fixed set of classes from in-memory bytecode. */
    private static final class InMemLoader extends ClassLoader {
        private final Map<String, byte[]> bytes;

        InMemLoader(Map<String, byte[]> bytes, ClassLoader parent) {
            super(parent);
            this.bytes = bytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] b = bytes.get(name);
            if (b == null) throw new ClassNotFoundException(name);
            return defineClass(name, b, 0, b.length);
        }
    }

}

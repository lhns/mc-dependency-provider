package de.lhns.mcdp.gradle.bridges;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0021 lambda-site coverage. Verifies the scanner detects {@code INVOKEDYNAMIC} on
 * {@code LambdaMetafactory}, recursively scans the synthetic body, and records the site for
 * the rewriter.
 */
class BridgeScannerLambdaTest {

    /**
     * The two {@code MethodType} slots of a {@code LambdaMetafactory} bsm-arg array, spelled
     * the way real javac spells them for {@code Supplier<String> s = () -> "x"} (checked
     * against {@code javap -v}; {@link LambdaBridgeRuntimeTest} compiles the real thing):
     * {@code bsmArgs[0]} carries the SAM's <em>erased</em> signature, {@code bsmArgs[2]} the
     * <em>instantiated</em> one, and the implementation method's own descriptor matches the
     * instantiated type.
     *
     * <p>These two MUST stay different values. A fixture that spells both slots
     * {@code ()Ljava/lang/Object;} agrees with an emitter that swaps or conflates them, and
     * that is precisely how ADR-0021 shipped broken with this suite green.</p>
     */
    private static final Type SAM_ERASED = Type.getType("()Ljava/lang/Object;");
    private static final Type SAM_INSTANTIATED = Type.getType("()Ljava/lang/String;");

    private final BridgePolicy policy = new BridgePolicy(
            List.of("com/example/api/"),
            "com.example.mod.mcdp_bridges");

    @Test
    void detectsLambdaMetafactoryIndyAndRecordsSite() {
        byte[] bytes = mixinWithLambda(
                "com/example/mod/MixinFoo",
                "java/util/function/Supplier",
                "()Ljava/util/function/Supplier;",
                false);
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.REWRITABLE, r.status());
        assertEquals(1, r.lambdaSites().size());
        LambdaSite site = r.lambdaSites().get(0);
        assertEquals("com/example/mod/MixinFoo", site.containerInternal());
        assertEquals("java/util/function/Supplier", site.samInternal());
        assertEquals("get", site.samMethodName());
        assertEquals("lambda$body$0", site.implMethod().getName());
        assertEquals(0, site.siteIndex());
    }

    @Test
    void detectsAltMetafactoryIndy() {
        byte[] bytes = mixinWithLambda(
                "com/example/mod/MixinFoo",
                "java/util/function/Supplier",
                "()Ljava/util/function/Supplier;",
                true);
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.REWRITABLE, r.status());
        assertEquals(1, r.lambdaSites().size());
    }

    @Test
    void recursiveScanCapturesModPrivateRefsInSyntheticBody() {
        // The synthetic lambda body INVOKESTATICs MyMod.compute(); the scanner should add it to
        // targets so the existing rewriter can route the synthetic's call through a bridge.
        byte[] bytes = mixinWithLambda(
                "com/example/mod/MixinFoo",
                "java/util/function/Supplier",
                "()Ljava/util/function/Supplier;",
                false);
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.REWRITABLE, r.status());
        assertTrue(r.targets().containsKey("com/example/mod/MyMod"),
                "expected synthetic body's INVOKESTATIC to land in targets: " + r.targets());
    }

    @Test
    void methodReferenceToModPrivateOwnerWarnsButDoesNotEmitSite() {
        // INVOKEDYNAMIC whose impl handle points at another class (method ref) — out of scope.
        byte[] bytes = mixinWithMethodReference(
                "com/example/mod/MixinFoo",
                "com/example/mod/MyMod");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        // Method-ref doesn't produce a lambda site; the scanner reports a warning.
        assertEquals(0, r.lambdaSites().size());
        assertFalse(r.warnings().isEmpty());
        assertTrue(r.warnings().get(0).contains("method reference"),
                "expected method-reference warning, got: " + r.warnings());
    }

    @Test
    void nonLambdaIndyEmitsWarning() {
        byte[] bytes = nonLambdaIndy("com/example/mod/MixinFoo");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(0, r.lambdaSites().size());
        assertFalse(r.warnings().isEmpty());
        assertTrue(r.warnings().get(0).contains("INVOKEDYNAMIC"),
                "expected non-lambda indy warning, got: " + r.warnings());
    }

    @Test
    void siteIndexIsStableAndPerClass() {
        // A class with two lambdas should get site indices 0 and 1.
        byte[] bytes = mixinWithTwoLambdas("com/example/mod/MixinFoo");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.REWRITABLE, r.status());
        assertEquals(2, r.lambdaSites().size());
        assertEquals(0, r.lambdaSites().get(0).siteIndex());
        assertEquals(1, r.lambdaSites().get(1).siteIndex());
    }

    @Test
    void capturedTypesDerivedFromIndyDescriptor() {
        // (Ljava/lang/String;)Ljava/util/function/Supplier; — captures one String.
        byte[] bytes = mixinWithCapturingLambda("com/example/mod/MixinFoo");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        LambdaSite site = r.lambdaSites().get(0);
        Type[] caps = site.capturedTypes();
        assertEquals(1, caps.length);
        assertEquals("Ljava/lang/String;", caps[0].getDescriptor());
    }

    @Test
    void siteCarriesTheOriginalBootstrapVerbatim() {
        // The wrapper emitter replays this metadata instead of re-deriving it, so the scanner
        // must hand it over untouched -- including the erased/instantiated MethodType split.
        byte[] bytes = mixinWithLambda(
                "com/example/mod/MixinFoo",
                "java/util/function/Supplier",
                "()Ljava/util/function/Supplier;",
                false);
        LambdaSite site = new BridgeScanner(policy).scan(bytes).lambdaSites().get(0);
        assertEquals(metafactoryBsm(), site.bsm());
        Object[] args = site.bsmArgs();
        assertEquals(3, args.length);
        assertEquals(SAM_ERASED, args[0],
                "samMethodType is the ERASED signature -- Supplier.get() erases to ()Object");
        assertEquals(SAM_INSTANTIATED, args[2],
                "instantiatedMethodType is the SPECIFIC one -- Supplier<String> gives ()String");
        assertNotEquals(args[0], args[2],
                "the two slots must hold different values, or this test cannot see them swapped");

        Object[] moved = site.relocatedBsmArgs("some/impl/Wrapper");
        assertEquals("some/impl/Wrapper", ((Handle) moved[1]).getOwner());
        assertEquals(site.implMethod().getName(), ((Handle) moved[1]).getName());
        assertEquals(site.implMethod().getDesc(), ((Handle) moved[1]).getDesc());
        assertEquals(args[0], moved[0]);
        assertEquals(args[2], moved[2]);
    }

    @Test
    void altMetafactoryTrailingArgsSurviveRelocation() {
        byte[] bytes = mixinWithLambda(
                "com/example/mod/MixinFoo",
                "java/util/function/Supplier",
                "()Ljava/util/function/Supplier;",
                true);
        LambdaSite site = new BridgeScanner(policy).scan(bytes).lambdaSites().get(0);
        Object[] moved = site.relocatedBsmArgs("some/impl/Wrapper");
        assertEquals(5, moved.length, "altMetafactory's flags word must be carried over");
        assertEquals(5, moved[3]);
        assertEquals(0, moved[4]);
    }

    @Test
    void instanceSyntheticLambdaIsRejectedWithAWarningNamingItsParts() {
        // A lambda capturing the receiver compiles to an instance synthetic. There is no sound
        // way to move it onto a wrapper class (the factory signature would have to name the
        // mixin), so the scanner must refuse the site rather than emit a static copy whose
        // ALOAD 0 reads a slot that no longer exists.
        byte[] bytes = mixinWithInstanceCapturingLambda("com/example/mod/MixinFoo");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(0, r.lambdaSites().size(), "instance synthetic must not yield a site");
        String w = r.warnings().stream().filter(x -> x.contains("captures"))
                .findFirst().orElse(null);
        assertTrue(w != null, "expected a rejection warning, got: " + r.warnings());
        assertTrue(w.contains("com.example.mod.MixinFoo"), w);
        assertTrue(w.contains("handler"), w);
        assertTrue(w.contains("lambda$body$0"), w);
    }

    @Test
    void indyOrdinalIsIndependentOfClassReaderFlags() {
        // The scanner reads with SKIP_FRAMES; the rewriter historically read with flags=0, and
        // a raw instruction index therefore drifted by one per FrameNode ahead of the site. The
        // recorded position must be identical under both.
        byte[] bytes = mixinWithBranchThenLambda("com/example/mod/MixinFoo");

        ClassNode skipped = new ClassNode();
        new ClassReader(bytes).accept(skipped, ClassReader.SKIP_FRAMES);
        ClassNode withFrames = new ClassNode();
        new ClassReader(bytes).accept(withFrames, 0);

        assertTrue(countFrames(withFrames) > 0,
                "fixture must actually carry StackMapTable frames or it proves nothing");
        assertEquals(0, countFrames(skipped));

        int a = new BridgeScanner(policy).scan(skipped).lambdaSites().get(0).indyOrdinal();
        int b = new BridgeScanner(policy).scan(withFrames).lambdaSites().get(0).indyOrdinal();
        assertEquals(a, b, "lambda site position must not depend on reader flags");
    }

    private static int countFrames(ClassNode cn) {
        int n = 0;
        for (MethodNode m : cn.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
                if (insn instanceof org.objectweb.asm.tree.FrameNode) n++;
            }
        }
        return n;
    }

    /** {@code handler()} whose lambda body is an INSTANCE synthetic capturing the receiver. */
    private static byte[] mixinWithInstanceCapturingLambda(String containerInternal) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, containerInternal, null,
                "java/lang/Object", null);
        ctor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC,
                "handler", "()Ljava/util/function/Supplier;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);   // capture #0 is the receiver
        Handle impl = new Handle(Opcodes.H_INVOKESPECIAL, containerInternal,
                "lambda$body$0", "()Ljava/lang/String;", false);
        mv.visitInvokeDynamicInsn("get",
                "(L" + containerInternal + ";)Ljava/util/function/Supplier;",
                metafactoryBsm(),
                SAM_ERASED,
                impl,
                SAM_INSTANTIATED);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor synth = cw.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,   // NOT static
                "lambda$body$0", "()Ljava/lang/String;", null, null);
        synth.visitCode();
        synth.visitLdcInsn("v");
        synth.visitInsn(Opcodes.ARETURN);
        synth.visitMaxs(0, 0);
        synth.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A branch (hence a StackMapTable frame) ahead of the lambda site. */
    private static byte[] mixinWithBranchThenLambda(String containerInternal) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, containerInternal, null,
                "java/lang/Object", null);
        ctor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "handler", "(Z)Ljava/util/function/Supplier;", null, null);
        mv.visitCode();
        org.objectweb.asm.Label join = new org.objectweb.asm.Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFEQ, join);
        mv.visitInsn(Opcodes.NOP);
        mv.visitLabel(join);
        Handle impl = new Handle(Opcodes.H_INVOKESTATIC, containerInternal,
                "lambda$body$0", "()Ljava/lang/String;", false);
        mv.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", metafactoryBsm(),
                SAM_ERASED, impl,
                SAM_INSTANTIATED);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor synth = cw.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$body$0", "()Ljava/lang/String;", null, null);
        synth.visitCode();
        synth.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/mod/MyMod", "compute",
                "()Ljava/lang/String;", false);
        synth.visitInsn(Opcodes.ARETURN);
        synth.visitMaxs(0, 0);
        synth.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Build a class with one method containing a single LambdaMetafactory indy. The
     * implementation method is a synthetic that INVOKESTATICs {@code com/example/mod/MyMod.compute}
     * to give the recursive scan something mod-private to find.
     */
    private static byte[] mixinWithLambda(String containerInternal, String samInternal,
                                           String indyDesc, boolean alt) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, containerInternal, null,
                "java/lang/Object", null);
        ctor(cw);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "handler", "()L" + samInternal + ";", null, null);
        mv.visitCode();
        Handle bsm = alt ? altMetafactoryBsm() : metafactoryBsm();
        Handle implMethod = new Handle(Opcodes.H_INVOKESTATIC, containerInternal,
                "lambda$body$0", "()Ljava/lang/String;", false);
        if (alt) {
            // altMetafactory takes the same three leading args plus a flags word (and, per
            // flag, further trailing args). FLAG_SERIALIZABLE | FLAG_MARKERS = 5, with a
            // marker-interface count of 0.
            mv.visitInvokeDynamicInsn("get", indyDesc, bsm,
                    SAM_ERASED,
                    implMethod,
                    SAM_INSTANTIATED,
                    5, 0);
        } else {
            mv.visitInvokeDynamicInsn("get", indyDesc, bsm,
                    SAM_ERASED,
                    implMethod,
                    SAM_INSTANTIATED);
        }
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // Synthetic lambda body: INVOKESTATIC MyMod.compute(); ARETURN
        MethodVisitor synth = cw.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$body$0", "()Ljava/lang/String;", null, null);
        synth.visitCode();
        synth.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/mod/MyMod", "compute",
                "()Ljava/lang/String;", false);
        synth.visitInsn(Opcodes.ARETURN);
        synth.visitMaxs(0, 0);
        synth.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] mixinWithMethodReference(String containerInternal, String referencedOwner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, containerInternal, null,
                "java/lang/Object", null);
        ctor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "handler", "()Ljava/util/function/Supplier;", null, null);
        mv.visitCode();
        Handle bsm = metafactoryBsm();
        // impl handle points at a DIFFERENT class — that's a method reference, not an inline lambda
        Handle implMethod = new Handle(Opcodes.H_INVOKESTATIC, referencedOwner,
                "compute", "()Ljava/lang/String;", false);
        mv.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", bsm,
                SAM_ERASED,
                implMethod,
                SAM_INSTANTIATED);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] nonLambdaIndy(String containerInternal) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, containerInternal, null,
                "java/lang/Object", null);
        ctor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "handler", "()V", null, null);
        mv.visitCode();
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC,
                "some/other/Bootstrap",
                "indyBoot",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false);
        mv.visitInvokeDynamicInsn("doSomething", "()V", bsm);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] mixinWithTwoLambdas(String containerInternal) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, containerInternal, null,
                "java/lang/Object", null);
        ctor(cw);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "handler", "()V", null, null);
        mv.visitCode();
        Handle bsm = metafactoryBsm();
        Handle implA = new Handle(Opcodes.H_INVOKESTATIC, containerInternal,
                "lambda$body$0", "()Ljava/lang/String;", false);
        Handle implB = new Handle(Opcodes.H_INVOKESTATIC, containerInternal,
                "lambda$body$1", "()Ljava/lang/String;", false);
        mv.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", bsm,
                SAM_ERASED, implA,
                SAM_INSTANTIATED);
        mv.visitInsn(Opcodes.POP);
        mv.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", bsm,
                SAM_ERASED, implB,
                SAM_INSTANTIATED);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor synthA = cw.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$body$0", "()Ljava/lang/String;", null, null);
        synthA.visitCode();
        synthA.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/mod/MyMod", "compute",
                "()Ljava/lang/String;", false);
        synthA.visitInsn(Opcodes.ARETURN);
        synthA.visitMaxs(0, 0);
        synthA.visitEnd();

        MethodVisitor synthB = cw.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$body$1", "()Ljava/lang/String;", null, null);
        synthB.visitCode();
        synthB.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/mod/MyMod", "compute",
                "()Ljava/lang/String;", false);
        synthB.visitInsn(Opcodes.ARETURN);
        synthB.visitMaxs(0, 0);
        synthB.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] mixinWithCapturingLambda(String containerInternal) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, containerInternal, null,
                "java/lang/Object", null);
        ctor(cw);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "handler", "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn("captured");
        Handle bsm = metafactoryBsm();
        Handle impl = new Handle(Opcodes.H_INVOKESTATIC, containerInternal,
                "lambda$body$0", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitInvokeDynamicInsn("get", "(Ljava/lang/String;)Ljava/util/function/Supplier;", bsm,
                SAM_ERASED,
                impl,
                SAM_INSTANTIATED);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor synth = cw.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$body$0", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        synth.visitCode();
        synth.visitVarInsn(Opcodes.ALOAD, 0);
        synth.visitInsn(Opcodes.ARETURN);
        synth.visitMaxs(0, 0);
        synth.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The real {@code LambdaMetafactory.metafactory}: a fixed 6-arg signature. A
     * {@code MethodHandle} constant resolves by exact name <em>and</em> descriptor, so a
     * fixture that pairs this name with {@code altMetafactory}'s varargs descriptor describes
     * a call site the JVM could never link — and then agrees with an emitter that makes the
     * same mistake. Both spellings live here so they cannot drift apart again.
     */
    static Handle metafactoryBsm() {
        return new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
    }

    /** {@code LambdaMetafactory.altMetafactory}: the varargs form. */
    static Handle altMetafactoryBsm() {
        return new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "altMetafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
    }

    private static void ctor(ClassWriter cw) {
        MethodVisitor c = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(1, 1);
        c.visitEnd();
    }
}

package de.lhns.mcdp.gradle.mixinbridges;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0021 lambda-site coverage. Verifies the scanner detects {@code INVOKEDYNAMIC} on
 * {@code LambdaMetafactory}, recursively scans the synthetic body, and records the site for
 * the rewriter.
 */
class BridgeMixinScannerLambdaTest {

    private final BridgePolicy policy = new BridgePolicy(
            List.of("com/example/api/"),
            "com.example.mod.mcdp_mixin_bridges");

    @Test
    void detectsLambdaMetafactoryIndyAndRecordsSite() {
        byte[] bytes = mixinWithLambda(
                "com/example/mod/MixinFoo",
                "java/util/function/Supplier",
                "()Ljava/util/function/Supplier;",
                false);
        MixinScanResult r = new BridgeMixinScanner(policy).scan(bytes);
        assertEquals(MixinScanResult.Status.REWRITABLE, r.status());
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
        MixinScanResult r = new BridgeMixinScanner(policy).scan(bytes);
        assertEquals(MixinScanResult.Status.REWRITABLE, r.status());
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
        MixinScanResult r = new BridgeMixinScanner(policy).scan(bytes);
        assertEquals(MixinScanResult.Status.REWRITABLE, r.status());
        assertTrue(r.targets().containsKey("com/example/mod/MyMod"),
                "expected synthetic body's INVOKESTATIC to land in targets: " + r.targets());
    }

    @Test
    void methodReferenceToModPrivateOwnerWarnsButDoesNotEmitSite() {
        // INVOKEDYNAMIC whose impl handle points at another class (method ref) — out of scope.
        byte[] bytes = mixinWithMethodReference(
                "com/example/mod/MixinFoo",
                "com/example/mod/MyMod");
        MixinScanResult r = new BridgeMixinScanner(policy).scan(bytes);
        // Method-ref doesn't produce a lambda site; the scanner reports a warning.
        assertEquals(0, r.lambdaSites().size());
        assertFalse(r.warnings().isEmpty());
        assertTrue(r.warnings().get(0).contains("method reference"),
                "expected method-reference warning, got: " + r.warnings());
    }

    @Test
    void nonLambdaIndyEmitsWarning() {
        byte[] bytes = nonLambdaIndy("com/example/mod/MixinFoo");
        MixinScanResult r = new BridgeMixinScanner(policy).scan(bytes);
        assertEquals(0, r.lambdaSites().size());
        assertFalse(r.warnings().isEmpty());
        assertTrue(r.warnings().get(0).contains("INVOKEDYNAMIC"),
                "expected non-lambda indy warning, got: " + r.warnings());
    }

    @Test
    void siteIndexIsStableAndPerClass() {
        // A class with two lambdas should get site indices 0 and 1.
        byte[] bytes = mixinWithTwoLambdas("com/example/mod/MixinFoo");
        MixinScanResult r = new BridgeMixinScanner(policy).scan(bytes);
        assertEquals(MixinScanResult.Status.REWRITABLE, r.status());
        assertEquals(2, r.lambdaSites().size());
        assertEquals(0, r.lambdaSites().get(0).siteIndex());
        assertEquals(1, r.lambdaSites().get(1).siteIndex());
    }

    @Test
    void capturedTypesDerivedFromIndyDescriptor() {
        // (Ljava/lang/String;)Ljava/util/function/Supplier; — captures one String.
        byte[] bytes = mixinWithCapturingLambda("com/example/mod/MixinFoo");
        MixinScanResult r = new BridgeMixinScanner(policy).scan(bytes);
        LambdaSite site = r.lambdaSites().get(0);
        Type[] caps = site.capturedTypes();
        assertEquals(1, caps.length);
        assertEquals("Ljava/lang/String;", caps[0].getDescriptor());
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
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                alt ? "altMetafactory" : "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        Handle implMethod = new Handle(Opcodes.H_INVOKESTATIC, containerInternal,
                "lambda$body$0", "()Ljava/lang/Object;", false);
        mv.visitInvokeDynamicInsn("get", indyDesc, bsm,
                Type.getType("()Ljava/lang/Object;"),
                implMethod,
                Type.getType("()Ljava/lang/Object;"));
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // Synthetic lambda body: INVOKESTATIC MyMod.compute(); ARETURN
        MethodVisitor synth = cw.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$body$0", "()Ljava/lang/Object;", null, null);
        synth.visitCode();
        synth.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/mod/MyMod", "compute",
                "()Ljava/lang/Object;", false);
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
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        // impl handle points at a DIFFERENT class — that's a method reference, not an inline lambda
        Handle implMethod = new Handle(Opcodes.H_INVOKESTATIC, referencedOwner,
                "compute", "()Ljava/lang/Object;", false);
        mv.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", bsm,
                Type.getType("()Ljava/lang/Object;"),
                implMethod,
                Type.getType("()Ljava/lang/Object;"));
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
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        Handle implA = new Handle(Opcodes.H_INVOKESTATIC, containerInternal,
                "lambda$body$0", "()Ljava/lang/Object;", false);
        Handle implB = new Handle(Opcodes.H_INVOKESTATIC, containerInternal,
                "lambda$body$1", "()Ljava/lang/Object;", false);
        mv.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", bsm,
                Type.getType("()Ljava/lang/Object;"), implA,
                Type.getType("()Ljava/lang/Object;"));
        mv.visitInsn(Opcodes.POP);
        mv.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", bsm,
                Type.getType("()Ljava/lang/Object;"), implB,
                Type.getType("()Ljava/lang/Object;"));
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor synthA = cw.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$body$0", "()Ljava/lang/Object;", null, null);
        synthA.visitCode();
        synthA.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/mod/MyMod", "compute",
                "()Ljava/lang/Object;", false);
        synthA.visitInsn(Opcodes.ARETURN);
        synthA.visitMaxs(0, 0);
        synthA.visitEnd();

        MethodVisitor synthB = cw.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$body$1", "()Ljava/lang/Object;", null, null);
        synthB.visitCode();
        synthB.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/mod/MyMod", "compute",
                "()Ljava/lang/Object;", false);
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
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        Handle impl = new Handle(Opcodes.H_INVOKESTATIC, containerInternal,
                "lambda$body$0", "(Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitInvokeDynamicInsn("get", "(Ljava/lang/String;)Ljava/util/function/Supplier;", bsm,
                Type.getType("()Ljava/lang/Object;"),
                impl,
                Type.getType("()Ljava/lang/Object;"));
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor synth = cw.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$body$0", "(Ljava/lang/String;)Ljava/lang/Object;", null, null);
        synth.visitCode();
        synth.visitVarInsn(Opcodes.ALOAD, 0);
        synth.visitInsn(Opcodes.ARETURN);
        synth.visitMaxs(0, 0);
        synth.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
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

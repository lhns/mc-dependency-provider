package de.lhns.mcdp.gradle.mixinbridges;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the ASM {@code COMPUTE_FRAMES} loader fix. Default
 * {@link ClassWriter#getCommonSuperClass} resolves common-supertype lookups against the
 * gradle-plugin classloader, which can't see Minecraft (or any other consumer-only) types. The
 * fix routes those lookups through a caller-supplied loader.
 *
 * <p>Reproducer shape: a tmp dir holds {@code net/minecraft/Base}, {@code net/minecraft/A
 * extends Base}, {@code net/minecraft/B extends Base} — platform-prefixed so the bridge policy
 * leaves them untouched. The mixin branches on its int arg, instantiates {@code A} on one path
 * and {@code B} on the other, then stores into a local — the merge-point stackmap-frame needs
 * {@code getCommonSuperClass("net/minecraft/A","net/minecraft/B")}. A separate mod-private
 * INVOKESTATIC ({@code pkg/Target.justRun()V}) keeps the scanner classifying the class as
 * REWRITABLE so the rewriter actually runs.</p>
 */
class ClasspathAwareClassWriterTest {

    private static final String BRIDGE_PKG = "frametest_bridges";
    private final BridgePolicy policy = new BridgePolicy(List.of(), BRIDGE_PKG);

    @Test
    void rewriteSucceedsWhenCommonSupertypeResolvableViaSuppliedLoader(@TempDir Path tmp)
            throws IOException {
        Path classesDir = writeStubHierarchy(tmp);
        try (URLClassLoader cp = new URLClassLoader(
                new URL[] { classesDir.toUri().toURL() }, ClassLoader.getPlatformClassLoader())) {
            byte[] mixinBytes = mixinForcingCommonSupertype(cp);
            BridgeMixinScanner scanner = new BridgeMixinScanner(policy);
            MixinScanResult result = scanner.scan(mixinBytes);
            assertTrue(result.status() == MixinScanResult.Status.REWRITABLE,
                    "scanner status: " + result.status());

            MixinRewriter rewriter = new MixinRewriter(policy, BRIDGE_PKG, cp);
            byte[] rewritten = rewriter.rewrite(mixinBytes, result.targets());
            assertNotNull(rewritten);
            assertTrue(rewritten.length > 0);
        }
    }

    @Test
    void rewriteFailsWithoutSuppliedLoader(@TempDir Path tmp) throws IOException {
        Path classesDir = writeStubHierarchy(tmp);
        byte[] mixinBytes;
        try (URLClassLoader fixtureCp = new URLClassLoader(
                new URL[] { classesDir.toUri().toURL() }, ClassLoader.getPlatformClassLoader())) {
            mixinBytes = mixinForcingCommonSupertype(fixtureCp);
        }
        BridgeMixinScanner scanner = new BridgeMixinScanner(policy);
        MixinScanResult result = scanner.scan(mixinBytes);
        // Drive the rewriter with the system loader (pre-fix behavior). net/minecraft/A and
        // net/minecraft/B aren't there → CNF inside getCommonSuperClass.
        MixinRewriter rewriter = new MixinRewriter(policy, BRIDGE_PKG);
        Throwable t = assertThrows(Throwable.class,
                () -> rewriter.rewrite(mixinBytes, result.targets()));
        boolean isCnf = false;
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ClassNotFoundException || c instanceof TypeNotPresentException) {
                isCnf = true;
                break;
            }
        }
        assertTrue(isCnf, "expected ClassNotFoundException in cause chain, got: " + t);
    }

    private static Path writeStubHierarchy(Path tmp) throws IOException {
        Path classesDir = tmp.resolve("classes");
        write(classesDir, "net/minecraft/Base", emitClass("net/minecraft/Base", "java/lang/Object"));
        write(classesDir, "net/minecraft/A", emitClass("net/minecraft/A", "net/minecraft/Base"));
        write(classesDir, "net/minecraft/B", emitClass("net/minecraft/B", "net/minecraft/Base"));
        // Mod-private static target — gives the scanner a REWRITABLE call site so the rewriter
        // actually runs and exercises the writer's frame-computation pass.
        write(classesDir, "pkg/Target", emitTargetClass());
        return classesDir;
    }

    private static void write(Path classesDir, String internalName, byte[] bytes) throws IOException {
        Path p = classesDir.resolve(internalName + ".class");
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
    }

    private static byte[] emitClass(String internalName, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] emitTargetClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "pkg/Target", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "justRun", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * <pre>
     * public static void handler(int cond) {
     *   Base b = (cond != 0) ? new A() : new B();
     *   b.toString();
     *   pkg.Target.justRun();   // mod-private call → scanner classifies REWRITABLE
     * }
     * </pre>
     * The merge-point frame after the if/else has {@code Base} on the locals; computing it
     * requires {@code getCommonSuperClass("net/minecraft/A","net/minecraft/B")} →
     * {@code "net/minecraft/Base"}. Building this fixture itself requires a writer with the
     * platform-prefixed types visible.
     */
    private static byte[] mixinForcingCommonSupertype(ClassLoader cp) {
        ClassWriter cw = new ClasspathAwareClassWriter(
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES, cp);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "pkg/Mixin", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "handler", "(I)V", null, null);
        mv.visitCode();
        Label elseBranch = new Label();
        Label merge = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFEQ, elseBranch);
        mv.visitTypeInsn(Opcodes.NEW, "net/minecraft/A");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/A", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitJumpInsn(Opcodes.GOTO, merge);
        mv.visitLabel(elseBranch);
        mv.visitTypeInsn(Opcodes.NEW, "net/minecraft/B");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/B", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitLabel(merge);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString",
                "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "pkg/Target", "justRun", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}

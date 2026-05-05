package de.lhns.mcdp.gradle.validate;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ADR-0024 validator A — over-share detection. Synthetic ASM-built classes
 * mirror the {@link de.lhns.mcdp.gradle.mixinbridges.BridgeMixinScannerTest} pattern.
 */
class SharedPackageContentValidatorTest {

    private final SharedPackageContentValidator validator =
            new SharedPackageContentValidator(List.of("com.example.shared.", "com.example.iface."));

    @Test
    void acceptsClassWithOnlyJdkRefs() {
        byte[] bytes = classWith("com/example/shared/OnlyJdk", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()Ljava/lang/String;", null, null);
            mv.visitCode();
            mv.visitLdcInsn("ok");
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        assertTrue(validator.validate(bytes).isEmpty());
    }

    @Test
    void acceptsClassWithOnlyMcRefs() {
        byte[] bytes = classWith("com/example/shared/OnlyMc", cw -> {
            FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC, "block", "Lnet/minecraft/world/level/block/Block;", null, null);
            fv.visitEnd();
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Lnet/fabricmc/loader/api/ModContainer;)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        assertTrue(validator.validate(bytes).isEmpty());
    }

    @Test
    void acceptsClassRefingAnotherSharedPackage() {
        // shared class in com/example/shared/ refs com/example/iface/MyApi which is also shared
        byte[] bytes = classWith("com/example/shared/UsesIface", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Lcom/example/iface/MyApi;)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        assertTrue(validator.validate(bytes).isEmpty());
    }

    @Test
    void rejectsClassWithScalaRef() {
        byte[] bytes = classWith("com/example/shared/UsesScala", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()Lscala/Option;", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        List<Diagnostic> diags = validator.validate(bytes);
        assertEquals(1, diags.size());
        assertEquals(Diagnostic.Kind.OVER_SHARE, diags.get(0).kind());
        assertEquals("com.example.shared.UsesScala", diags.get(0).fromClass());
        assertTrue(diags.get(0).offendingRefs().contains("scala.Option"));
        assertTrue(diags.get(0).message().contains("scala.Option"));
        assertTrue(diags.get(0).message().contains("NoClassDefFoundError"));
    }

    @Test
    void rejectsClassWithKotlinRef() {
        byte[] bytes = classWith("com/example/shared/UsesKotlin", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Lkotlin/collections/CollectionsKt;)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        List<Diagnostic> diags = validator.validate(bytes);
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).offendingRefs().contains("kotlin.collections.CollectionsKt"));
    }

    @Test
    void rejectsClassWithModPrivateRef() {
        // shared class refs com.example.modprivate.Helper which is NOT shared
        byte[] bytes = classWith("com/example/shared/UsesPrivate", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Lcom/example/modprivate/Helper;)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        List<Diagnostic> diags = validator.validate(bytes);
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).offendingRefs().contains("com.example.modprivate.Helper"));
    }

    @Test
    void multipleOffendingRefsListed() {
        byte[] bytes = classWith("com/example/shared/Multi", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Lscala/Option;Lkotlin/Pair;)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        List<Diagnostic> diags = validator.validate(bytes);
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).offendingRefs().contains("scala.Option"));
        assertTrue(diags.get(0).offendingRefs().contains("kotlin.Pair"));
    }

    @Test
    void descriptorRefsCounted() {
        // method descriptor with no opcode use of the type — pure descriptor ref
        byte[] bytes = classWith("com/example/shared/DescOnly", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Lscala/Option;)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        List<Diagnostic> diags = validator.validate(bytes);
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).offendingRefs().contains("scala.Option"));
    }

    @Test
    void genericSignatureRefsCounted() {
        // field with generic signature referencing scala.Option
        byte[] bytes = classWith("com/example/shared/Generic", cw -> {
            FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC, "f",
                    "Ljava/util/List;", "Ljava/util/List<Lscala/Option;>;", null);
            fv.visitEnd();
        });
        List<Diagnostic> diags = validator.validate(bytes);
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).offendingRefs().contains("scala.Option"));
    }

    @Test
    void classOutsideSharedPackagesIsIgnored() {
        // class in com.example.modprivate (not shared) — validator returns no diagnostics
        // even though it refs scala.Option, because the class itself isn't being moved
        // onto the platform loader by sharedPackages
        byte[] bytes = classWith("com/example/modprivate/Whatever", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Lscala/Option;)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        assertTrue(validator.validate(bytes).isEmpty());
    }

    /** Build a minimal class with one method/field configured by the caller. */
    private static byte[] classWith(String internalName, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        body.accept(cw);
        // <init>
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

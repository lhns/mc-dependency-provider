package de.lhns.mcdp.gradle.validate;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ADR-0024 validator B — under-share detection (cross-loader casts).
 */
class CrossLoaderCastValidatorTest {

    private static final List<String> MIXIN_ONLY = List.of("org.spongepowered.asm.mixin.Mixin");

    @Test
    void acceptsCastInsideMixinClass() {
        // Both classes are @Mixin-annotated; the cast is mixin-to-mixin, both load on game layer.
        byte[] mixinAccessor = mixinAccessor("com/example/mod/mixin/BlockEntityAccessor");
        byte[] mixinCaller = classWith("com/example/mod/mixin/BlockEntityMixin", cw -> {
            cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", true).visitEnd();
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Ljava/lang/Object;)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "com/example/mod/mixin/BlockEntityAccessor");
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        var validator = new CrossLoaderCastValidator(MIXIN_ONLY, List.of());
        assertTrue(validator.validate(List.of(mixinAccessor, mixinCaller)).isEmpty());
    }

    @Test
    void rejectsCastFromModCodeToMixinAccessor() {
        byte[] mixinAccessor = mixinAccessor("com/example/mod/mixin/BlockEntityAccessor");
        byte[] modCode = classWith("com/example/mod/util/WorldUtil", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Ljava/lang/Object;)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "com/example/mod/mixin/BlockEntityAccessor");
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        var validator = new CrossLoaderCastValidator(MIXIN_ONLY, List.of());
        List<Diagnostic> diags = validator.validate(List.of(mixinAccessor, modCode));
        assertEquals(1, diags.size());
        assertEquals(Diagnostic.Kind.UNDER_SHARE, diags.get(0).kind());
        assertEquals("com.example.mod.util.WorldUtil", diags.get(0).fromClass());
        assertTrue(diags.get(0).message().contains("sharedPackages.add('com.example.mod.mixin.')"));
    }

    @Test
    void rejectsInstanceofFromModCode() {
        byte[] mixinAccessor = mixinAccessor("com/example/mod/mixin/BlockEntityAccessor");
        byte[] modCode = classWith("com/example/mod/util/IsAccessor", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Ljava/lang/Object;)Z", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, "com/example/mod/mixin/BlockEntityAccessor");
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        var validator = new CrossLoaderCastValidator(MIXIN_ONLY, List.of());
        List<Diagnostic> diags = validator.validate(List.of(mixinAccessor, modCode));
        assertEquals(1, diags.size());
    }

    @Test
    void rejectsConstantPoolRef() {
        byte[] mixinAccessor = mixinAccessor("com/example/mod/mixin/BlockEntityAccessor");
        byte[] modCode = classWith("com/example/mod/util/Reflective", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()Ljava/lang/Class;", null, null);
            mv.visitCode();
            mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType("com/example/mod/mixin/BlockEntityAccessor"));
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        var validator = new CrossLoaderCastValidator(MIXIN_ONLY, List.of());
        List<Diagnostic> diags = validator.validate(List.of(mixinAccessor, modCode));
        assertEquals(1, diags.size());
    }

    @Test
    void rejectsMethodSignatureRef() {
        byte[] mixinAccessor = mixinAccessor("com/example/mod/mixin/BlockEntityAccessor");
        byte[] modCode = classWith("com/example/mod/util/Sig", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f",
                    "(Lcom/example/mod/mixin/BlockEntityAccessor;)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        var validator = new CrossLoaderCastValidator(MIXIN_ONLY, List.of());
        List<Diagnostic> diags = validator.validate(List.of(mixinAccessor, modCode));
        assertEquals(1, diags.size());
    }

    @Test
    void rejectsFieldTypeRef() {
        byte[] mixinAccessor = mixinAccessor("com/example/mod/mixin/BlockEntityAccessor");
        byte[] modCode = classWith("com/example/mod/util/Holder", cw -> {
            FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC, "x",
                    "Lcom/example/mod/mixin/BlockEntityAccessor;", null, null);
            fv.visitEnd();
        });
        var validator = new CrossLoaderCastValidator(MIXIN_ONLY, List.of());
        List<Diagnostic> diags = validator.validate(List.of(mixinAccessor, modCode));
        assertEquals(1, diags.size());
    }

    @Test
    void acceptsRefToAccessorWhenPackageShared() {
        byte[] mixinAccessor = mixinAccessor("com/example/mod/mixin/BlockEntityAccessor");
        byte[] modCode = classWith("com/example/mod/util/WorldUtil", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Ljava/lang/Object;)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "com/example/mod/mixin/BlockEntityAccessor");
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        // sharedPackages includes the accessor's package — no diagnostic
        var validator = new CrossLoaderCastValidator(MIXIN_ONLY, List.of("com.example.mod.mixin."));
        assertTrue(validator.validate(List.of(mixinAccessor, modCode)).isEmpty());
    }

    @Test
    void respectsConfigurableAnnotationList() {
        byte[] customAnnotated = classWith("com/example/mod/api/MyType", cw -> {
            cw.visitAnnotation("Lcom/example/MyAnno;", true).visitEnd();
        });
        byte[] modCode = classWith("com/example/mod/util/Caller", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Lcom/example/mod/api/MyType;)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        // With default Mixin-only annotations: no diagnostic (MyType isn't a Mixin)
        var defaults = new CrossLoaderCastValidator(MIXIN_ONLY, List.of());
        assertTrue(defaults.validate(List.of(customAnnotated, modCode)).isEmpty());

        // With custom annotation: diagnostic fires, proving the list isn't hardcoded
        var custom = new CrossLoaderCastValidator(List.of("com.example.MyAnno"), List.of());
        List<Diagnostic> diags = custom.validate(List.of(customAnnotated, modCode));
        assertEquals(1, diags.size());
        assertEquals("com.example.mod.util.Caller", diags.get(0).fromClass());
    }

    @Test
    void ignoresMixinWithoutAccessorMethods() {
        // @Mixin interface with no @Accessor or @Invoker methods — pure target-only mixin
        // (e.g. @Mixin(BlockEntity.class) class FooMixin { @Inject ... }). The Class identity
        // mismatch only matters when mod code casts to USER-VISIBLE types; pure injectors are
        // never cast to.
        byte[] pureMixin = classWith("com/example/mod/mixin/PureMixin", cw -> {
            cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", true).visitEnd();
            // no @Accessor / @Invoker methods
        });
        byte[] modCode = classWith("com/example/mod/util/Caller", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Lcom/example/mod/mixin/PureMixin;)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        var validator = new CrossLoaderCastValidator(MIXIN_ONLY, List.of());
        assertTrue(validator.validate(List.of(pureMixin, modCode)).isEmpty());
    }

    /**
     * Build a synthetic Mixin accessor: {@code @Mixin}-annotated interface declaring one
     * {@code @Accessor}-annotated method.
     */
    private static byte[] mixinAccessor(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                internalName, null, "java/lang/Object", null);
        AnnotationVisitor av = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", true);
        av.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "getX", "()I", null, null);
        AnnotationVisitor mav = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", true);
        mav.visitEnd();
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classWith(String internalName, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        body.accept(cw);
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

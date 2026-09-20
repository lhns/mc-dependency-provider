package de.lhns.mcdp.gradle.validate;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the places a type can hide in a class file. Every case here was a FALSE NEGATIVE
 * before: the reference is invisible to the collector, so both ADR-0024 validators pass and the
 * over-share ships, surfacing as {@code NoClassDefFoundError} (or a verification failure, for the
 * exception table) the first time the shared class is linked on the platform loader.
 *
 * <p>Each test asserts the marker type {@code scala/Option} — a stand-in for "anything on the
 * per-mod loader's URLs only" — comes back in the collected set.
 */
class ClassRefCollectorTest {

    private static final String MARKER = "scala/Option";

    @Test
    void collectsCatchTypes() {
        Set<String> refs = refsOf(cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            Label start = new Label(), end = new Label(), handler = new Label();
            mv.visitCode();
            mv.visitTryCatchBlock(start, end, handler, MARKER);
            mv.visitLabel(start);
            mv.visitInsn(Opcodes.NOP);
            mv.visitLabel(end);
            Label after = new Label();
            mv.visitJumpInsn(Opcodes.GOTO, after);
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(after);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(4, 4);
            mv.visitEnd();
        });
        assertTrue(refs.contains(MARKER), refs.toString());
    }

    @Test
    void ignoresFinallyHandlerWithNullCatchType() {
        // A `finally` block has a null exception type; the walker must not NPE on it.
        Set<String> refs = refsOf(cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            Label start = new Label(), end = new Label(), handler = new Label();
            mv.visitCode();
            mv.visitTryCatchBlock(start, end, handler, null);
            mv.visitLabel(start);
            mv.visitInsn(Opcodes.NOP);
            mv.visitLabel(end);
            Label after = new Label();
            mv.visitJumpInsn(Opcodes.GOTO, after);
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(after);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(4, 4);
            mv.visitEnd();
        });
        assertTrue(refs.contains("java/lang/Object"), refs.toString());
    }

    @Test
    void collectsAnnotationClassMember() {
        // @Something(MyModClass.class) — only the annotation's own desc was collected before.
        Set<String> refs = refsOf(cw -> {
            AnnotationVisitor av = cw.visitAnnotation("Lcom/example/anno/Something;", true);
            av.visit("value", Type.getObjectType(MARKER));
            av.visitEnd();
        });
        assertTrue(refs.contains(MARKER), refs.toString());
    }

    @Test
    void collectsAnnotationEnumMember() {
        Set<String> refs = refsOf(cw -> {
            AnnotationVisitor av = cw.visitAnnotation("Lcom/example/anno/Something;", true);
            av.visitEnum("mode", "L" + MARKER + ";", "FAST");
            av.visitEnd();
        });
        assertTrue(refs.contains(MARKER), refs.toString());
    }

    @Test
    void collectsNestedAnnotationAndArrayMembers() {
        Set<String> refs = refsOf(cw -> {
            AnnotationVisitor av = cw.visitAnnotation("Lcom/example/anno/Outer;", true);
            AnnotationVisitor nested = av.visitAnnotation("inner", "Lcom/example/anno/Inner;");
            AnnotationVisitor array = nested.visitArray("types");
            array.visit(null, Type.getObjectType(MARKER));
            array.visitEnd();
            nested.visitEnd();
            av.visitEnd();
        });
        assertTrue(refs.contains(MARKER), refs.toString());
        assertTrue(refs.contains("com/example/anno/Inner"), refs.toString());
    }

    @Test
    void collectsParameterAnnotations() {
        Set<String> refs = refsOf(cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(I)V", null, null);
            AnnotationVisitor av = mv.visitParameterAnnotation(0, "Lcom/example/anno/Something;", true);
            av.visit("value", Type.getObjectType(MARKER));
            av.visitEnd();
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(4, 4);
            mv.visitEnd();
        });
        assertTrue(refs.contains(MARKER), refs.toString());
    }

    @Test
    void collectsInvokeDynamicBootstrapHandleAndHandleArgs() {
        // The shape javac emits for a method reference: the LambdaMetafactory bsm plus an
        // implementation Handle whose owner/descriptor name the real types.
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        Handle impl = new Handle(Opcodes.H_INVOKESTATIC,
                MARKER, "apply", "()Ljava/lang/Object;", false);
        Set<String> refs = refsOf(cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            mv.visitCode();
            mv.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", bsm,
                    Type.getMethodType("()Ljava/lang/Object;"), impl,
                    Type.getMethodType("()Ljava/lang/Object;"));
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(4, 4);
            mv.visitEnd();
        });
        assertTrue(refs.contains(MARKER), refs.toString());
        assertTrue(refs.contains("java/lang/invoke/LambdaMetafactory"), refs.toString());
    }

    @Test
    void collectsLdcMethodHandleConstant() {
        Handle h = new Handle(Opcodes.H_GETSTATIC, "com/example/Holder", "INSTANCE",
                "L" + MARKER + ";", false);
        Set<String> refs = refsOf(cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            mv.visitCode();
            mv.visitLdcInsn(h);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(4, 4);
            mv.visitEnd();
        });
        assertTrue(refs.contains(MARKER), refs.toString());
        assertTrue(refs.contains("com/example/Holder"), refs.toString());
    }

    @Test
    void collectsLdcConstantDynamic() {
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, "com/example/Bootstraps", "lazy",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)"
                        + "Ljava/lang/Object;",
                false);
        ConstantDynamic condy = new ConstantDynamic("value", "L" + MARKER + ";", bsm);
        Set<String> refs = refsOf(cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            mv.visitCode();
            mv.visitLdcInsn(condy);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(4, 4);
            mv.visitEnd();
        });
        assertTrue(refs.contains(MARKER), refs.toString());
        assertTrue(refs.contains("com/example/Bootstraps"), refs.toString());
    }

    @Test
    void collectsLdcMethodTypeArgumentAndReturnTypes() {
        Set<String> refs = refsOf(cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            mv.visitCode();
            mv.visitLdcInsn(Type.getMethodType("(L" + MARKER + ";)Lcom/example/Result;"));
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(4, 4);
            mv.visitEnd();
        });
        assertTrue(refs.contains(MARKER), refs.toString());
        assertTrue(refs.contains("com/example/Result"), refs.toString());
    }

    @Test
    void collectsLocalVariableDescriptors() {
        Set<String> refs = refsOf(cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            Label start = new Label(), end = new Label();
            mv.visitCode();
            mv.visitLabel(start);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, 1);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(end);
            mv.visitLocalVariable("opt", "L" + MARKER + ";", null, start, end, 1);
            mv.visitMaxs(4, 4);
            mv.visitEnd();
        });
        assertTrue(refs.contains(MARKER), refs.toString());
    }

    @Test
    void collectsNestAndInnerClassTables() {
        Set<String> refs = refsOf(cw -> {
            cw.visitNestHost(MARKER);
            cw.visitInnerClass("com/example/Outer$Inner", "com/example/Outer", "Inner", Opcodes.ACC_STATIC);
        });
        assertTrue(refs.contains(MARKER), refs.toString());
        assertTrue(refs.contains("com/example/Outer$Inner"), refs.toString());
        assertTrue(refs.contains("com/example/Outer"), refs.toString());
    }

    @Test
    void collectsPermittedSubclasses() {
        Set<String> refs = refsOf(cw -> cw.visitPermittedSubclass(MARKER));
        assertTrue(refs.contains(MARKER), refs.toString());
    }

    @Test
    void collectsRecordComponents() {
        Set<String> refs = refsOf(cw -> cw.visitRecordComponent("opt", "L" + MARKER + ";", null).visitEnd());
        assertTrue(refs.contains(MARKER), refs.toString());
    }

    @Test
    void collectsTypeAnnotations() {
        Set<String> refs = refsOf(cw -> {
            // Type annotation on the class's supertype reference.
            AnnotationVisitor av = cw.visitTypeAnnotation(
                    org.objectweb.asm.TypeReference.newSuperTypeReference(-1).getValue(),
                    null, "Lcom/example/anno/Nullable;", true);
            av.visit("value", Type.getObjectType(MARKER));
            av.visitEnd();
        });
        assertTrue(refs.contains(MARKER), refs.toString());
        assertTrue(refs.contains("com/example/anno/Nullable"), refs.toString());
    }

    /** Build a minimal class named {@code com/example/shared/Probe} configured by the caller. */
    private static Set<String> refsOf(Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/shared/Probe", null,
                "java/lang/Object", null);
        body.accept(cw);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        cw.visitEnd();
        return ClassRefCollector.collect(cw.toByteArray());
    }
}

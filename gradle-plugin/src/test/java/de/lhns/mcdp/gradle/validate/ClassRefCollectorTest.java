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

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * LocalVariableTable descriptors are deliberately not collected: the JVM never resolves one
     * (JVMS 4.7.13), so they can only produce false positives. This is not hypothetical --
     * BridgeRewriter preserves the LVT verbatim, so a javac-compiled `T t = bridgedCall();` in a
     * shared package kept an entry naming the mod-private T and failed a mod that runs clean.
     *
     * <p>Mutation caught: restoring the `m.localVariables` loop in {@link ClassRefCollector}.
     */
    @Test
    void doesNotCollectLocalVariableDescriptors() {
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
        assertFalse(refs.contains(MARKER),
                "an LVT descriptor is debug-only and must not be collected: " + refs);
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

    /**
     * CHECKCAST/INSTANCEOF/ANEWARRAY on an array type carry a descriptor, not an internal
     * name. Collecting the raw operand yields "[Ljava/lang/String;", which no prefix check
     * recognises as a platform type, so validateSharedPackages failed the build on a
     * shared-package class containing nothing worse than `(String[]) o`.
     */
    @Test
    void arrayTypeInstructionsCollectTheElementTypeNotTheDescriptor() {
        Set<String> refs = refsOf(cw -> {
            MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "m",
                    "(Ljava/lang/Object;)V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/String;");
            m.visitInsn(Opcodes.POP);
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitTypeInsn(Opcodes.INSTANCEOF, "[Lcom/example/modcode/Thing;");
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(2, 2);
            m.visitEnd();
        });
        assertTrue(refs.contains("java/lang/String"), refs.toString());
        assertTrue(refs.contains("com/example/modcode/Thing"), refs.toString());
        assertFalse(refs.contains("[Ljava/lang/String;"), "raw array descriptor leaked: " + refs);
        assertFalse(refs.contains("[Lcom/example/modcode/Thing;"), "raw array descriptor leaked: " + refs);
    }

    /** {@code arr.clone()} puts an array descriptor in MethodInsnNode.owner. */
    @Test
    void arrayMemberInvocationCollectsTheElementType() {
        Set<String> refs = refsOf(cw -> {
            MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "m",
                    "([Lcom/example/modcode/Thing;)V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[Lcom/example/modcode/Thing;",
                    "clone", "()Ljava/lang/Object;", false);
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(2, 2);
            m.visitEnd();
        });
        assertTrue(refs.contains("com/example/modcode/Thing"), refs.toString());
        assertFalse(refs.contains("[Lcom/example/modcode/Thing;"), "raw array descriptor leaked: " + refs);
    }

    /** Primitive arrays have no element class at all and must not leak a descriptor either. */
    @Test
    void primitiveArrayCastCollectsNothing() {
        Set<String> refs = refsOf(cw -> {
            MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "m",
                    "(Ljava/lang/Object;)V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitTypeInsn(Opcodes.CHECKCAST, "[[I");
            m.visitInsn(Opcodes.POP);
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(2, 2);
            m.visitEnd();
        });
        assertFalse(refs.contains("[[I"), "raw array descriptor leaked: " + refs);
    }

    // ---- bridge awareness (collect(ClassNode, String)) --------------------------------------

    private static final String BRIDGE_PKG = "com.example.mcdp_bridges";
    private static final String BRIDGE = "com/example/mcdp_bridges/ConfigBridge";

    /**
     * The one exemption: a call whose OWNER is a generated bridge carries the mod-private type it
     * exists to reach in its own descriptor. The bridge is recorded, what it reaches is not.
     *
     * <p>Mutation caught: dropping the {@code isBridgeOwner} guard on the {@code MethodInsnNode}
     * branch of {@link ClassRefCollector} (i.e. going back to an unconditional
     * {@code addMethodType(mi.desc, refs)}) — the false positive this whole change exists to
     * kill comes straight back.
     */
    @Test
    void bridgeOwnedDescriptorTypesAreNotCollected() {
        Set<String> refs = refsOf(BRIDGE_PKG, cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitTypeInsn(Opcodes.CHECKCAST, BRIDGE);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, BRIDGE, "isEnabledFor",
                    "(L" + MARKER + ";)Z", true);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();
        });
        assertFalse(refs.contains(MARKER), "bridge descriptor type leaked: " + refs);
        assertTrue(refs.contains(BRIDGE), "bridge owner itself must stay collected: " + refs);
    }

    /**
     * Same call, no bridge package configured: nothing is exempt. Guards against the exemption
     * turning on by accident for callers that pass nothing (notably
     * {@link CrossLoaderCastValidator}, which still uses the one-argument overload).
     *
     * <p>Mutation caught: making {@code bridgeOwnerPrefix(null)} fall back to a non-null value,
     * or having {@code collect(ClassNode)} delegate with anything other than {@code null}.
     */
    @Test
    void withoutABridgePackageNothingIsExempt() {
        Set<String> refs = refsOf(cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitTypeInsn(Opcodes.CHECKCAST, BRIDGE);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, BRIDGE, "isEnabledFor",
                    "(L" + MARKER + ";)Z", true);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();
        });
        assertTrue(refs.contains(MARKER), "exemption applied with no bridge package: " + refs);
    }

    /**
     * A {@code MethodHandle} constant naming a bridge method is NOT exempt: resolving a handle
     * eagerly loads every class in its descriptor with the referencing class's loader, so unlike
     * an invoke instruction it really would fail on the platform loader. (The rewriter never
     * emits one — lambda sites become {@code INVOKEINTERFACE make(..)} — so this costs nothing.)
     *
     * <p>Mutation caught: pushing the bridge-owner check down into {@code addHandle} or
     * {@code addConstant} "for symmetry", which would hide a genuine hard failure.
     */
    @Test
    void bridgeOwnedMethodHandleDescriptorIsStillCollected() {
        Set<String> refs = refsOf(BRIDGE_PKG, cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            mv.visitCode();
            mv.visitLdcInsn(new Handle(Opcodes.H_INVOKEINTERFACE, BRIDGE, "isEnabledFor",
                    "(L" + MARKER + ";)Z", true));
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        });
        assertTrue(refs.contains(MARKER), "method-handle descriptor wrongly exempted: " + refs);
    }

    /**
     * The sibling {@code <bridgePackage>_impl} package is not shared (ADR-0021 errata), so it
     * gets no exemption — and neither does any other package that merely starts with the bridge
     * package's spelling.
     *
     * <p>Mutation caught: dropping the trailing {@code '/'} from
     * {@code ClassRefCollector.bridgeOwnerPrefix}, which would make {@code mcdp_bridges_impl}
     * (and {@code mcdp_bridgesAnything}) match the prefix.
     */
    @Test
    void implPackageGetsNoExemption() {
        String impl = "com/example/mcdp_bridges_impl/ConfigBridgeImpl";
        Set<String> refs = refsOf(BRIDGE_PKG, cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitTypeInsn(Opcodes.CHECKCAST, impl);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, impl, "isEnabledFor",
                    "(L" + MARKER + ";)Z", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();
        });
        assertTrue(refs.contains(MARKER), "_impl descriptor wrongly exempted: " + refs);
        assertTrue(refs.contains(impl), "_impl owner must be collected: " + refs);
    }

    /** Build a minimal class named {@code com/example/shared/Probe} configured by the caller. */
    private static Set<String> refsOf(Consumer<ClassWriter> body) {
        return refsOf(null, body);
    }

    private static Set<String> refsOf(String bridgePackage, Consumer<ClassWriter> body) {
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
        return ClassRefCollector.collect(cw.toByteArray(), bridgePackage);
    }
}

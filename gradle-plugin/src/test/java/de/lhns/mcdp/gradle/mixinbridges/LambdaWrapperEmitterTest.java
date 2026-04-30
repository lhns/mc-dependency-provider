package de.lhns.mcdp.gradle.mixinbridges;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0021 wrapper-emitter unit. Verifies that for a single lambda site the emitter produces
 * a bridge interface + impl pair with the expected shape, and that the impl's {@code make}
 * body uses {@code INVOKEDYNAMIC LambdaMetafactory} pointing at an embedded synthetic.
 */
class LambdaWrapperEmitterTest {

    private final LambdaWrapperEmitter emitter = new LambdaWrapperEmitter(
            "com.example.mod.mcdp_mixin_bridges", LambdaWrapperEmitterTest.class.getClassLoader());

    @Test
    void emitsBridgeInterfaceWithSingleAbstractMakeMethod() {
        Result r = build();

        ClassNode iface = parse(r.art.bridgeIfaceBytes);
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                iface.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT));
        assertEquals(1, iface.methods.size());
        MethodNode make = iface.methods.get(0);
        assertEquals("make", make.name);
        assertEquals("()Ljava/util/function/Supplier;", make.desc);
        assertTrue((make.access & Opcodes.ACC_ABSTRACT) != 0);
    }

    @Test
    void emitsBridgeImplWithMakeAndEmbeddedSynthetic() {
        Result r = build();

        ClassNode impl = parse(r.art.bridgeImplBytes);
        assertEquals(r.art.bridgeImplInternal, impl.name);
        assertTrue(impl.interfaces.contains(r.art.bridgeIfaceInternal));

        // Has: <init>, make, lambda$body$0
        assertNotNull(findMethod(impl, "<init>", "()V"));
        MethodNode make = findMethod(impl, "make", "()Ljava/util/function/Supplier;");
        assertNotNull(make);
        MethodNode synth = findMethod(impl, "lambda$body$0", "()Ljava/lang/Object;");
        assertNotNull(synth, "expected the synthetic to be embedded on the impl class");
        assertTrue((synth.access & Opcodes.ACC_PRIVATE) != 0);
        assertTrue((synth.access & Opcodes.ACC_STATIC) != 0);

        // make's body uses INVOKEDYNAMIC LambdaMetafactory pointing at the embedded synthetic.
        boolean foundIndy = false;
        for (AbstractInsnNode insn : make.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) {
                assertEquals("get", indy.name);
                Handle bsm = indy.bsm;
                assertEquals("java/lang/invoke/LambdaMetafactory", bsm.getOwner());
                assertEquals("metafactory", bsm.getName());
                Handle implHandle = (Handle) indy.bsmArgs[1];
                assertEquals(r.art.bridgeImplInternal, implHandle.getOwner(),
                        "impl-method handle should point at the embedded synthetic on the impl");
                assertEquals("lambda$body$0", implHandle.getName());
                foundIndy = true;
            }
        }
        assertTrue(foundIndy, "make body must contain INVOKEDYNAMIC LambdaMetafactory");
    }

    @Test
    void implMakeBodyContainsNoDirectModPrivateReferenceOnEmptyCaptureCase() {
        // The impl class is loaded by ModClassLoader at runtime — direct mod-private refs in
        // the synthetic's body are fine. But the make method itself shouldn't reference
        // mod-private types beyond the implMethod handle (which is on this same impl class).
        Result r = build();
        ClassNode impl = parse(r.art.bridgeImplBytes);
        MethodNode make = findMethod(impl, "make", "()Ljava/util/function/Supplier;");
        for (AbstractInsnNode insn : make.instructions) {
            if (insn instanceof MethodInsnNode min) {
                assertTrue(!min.owner.startsWith("com/example/mod/"),
                        "make body should not directly reference mod-private types: " + min.owner);
            }
        }
    }

    @Test
    void logicFieldNameIsStable() {
        assertEquals("LAMBDA_MixinFoo_0",
                LambdaWrapperEmitter.logicFieldName("MixinFoo", 0));
        assertEquals("LAMBDA_MixinFoo_3",
                LambdaWrapperEmitter.logicFieldName("MixinFoo", 3));
        assertEquals("LAMBDA_Inner_Class_2",
                LambdaWrapperEmitter.logicFieldName("Inner$Class", 2));
    }

    @Test
    void capturedFieldDescriptorMatchesIndySignature() {
        // (Ljava/lang/String;)Lsam; — one captured String. The make method signature mirrors
        // it: (Ljava/lang/String;)Lsam;.
        ClassNode container = container("com/example/mod/MixinFoo");
        // synthetic that takes a String and returns Object (matching capture + SAM).
        MethodNode synthetic = staticSynthetic(container, "lambda$body$0",
                "(Ljava/lang/String;)Ljava/lang/Object;");
        Handle implHandle = new Handle(Opcodes.H_INVOKESTATIC, container.name,
                "lambda$body$0", "(Ljava/lang/String;)Ljava/lang/Object;", false);
        LambdaSite site = new LambdaSite(container.name, "method()V", 0,
                "java/util/function/Supplier", "get", implHandle,
                "(Ljava/lang/String;)Ljava/util/function/Supplier;", 0);

        LambdaWrapperEmitter.Artifacts art = emitter.emit(container, site, synthetic);
        ClassNode iface = parse(art.bridgeIfaceBytes);
        MethodNode make = iface.methods.get(0);
        assertEquals("(Ljava/lang/String;)Ljava/util/function/Supplier;", make.desc);
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (Objects.equals(m.name, name) && Objects.equals(m.desc, desc)) return m;
        }
        return null;
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    /** End-to-end fixture: container with a no-capture supplier lambda. */
    private Result build() {
        ClassNode container = container("com/example/mod/MixinFoo");
        MethodNode synthetic = staticSynthetic(container, "lambda$body$0", "()Ljava/lang/Object;");
        Handle implHandle = new Handle(Opcodes.H_INVOKESTATIC, container.name,
                "lambda$body$0", "()Ljava/lang/Object;", false);
        LambdaSite site = new LambdaSite(container.name, "handler()V", 0,
                "java/util/function/Supplier", "get", implHandle,
                "()Ljava/util/function/Supplier;", 0);
        LambdaWrapperEmitter.Artifacts art = emitter.emit(container, site, synthetic);
        return new Result(art);
    }

    private static ClassNode container(String internalName) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V21;
        cn.access = Opcodes.ACC_PUBLIC;
        cn.name = internalName;
        cn.superName = "java/lang/Object";
        cn.interfaces = new ArrayList<>();
        cn.fields = new ArrayList<>();
        cn.methods = new ArrayList<>();
        return cn;
    }

    /** Add a private-static-synthetic that calls com/example/mod/MyMod.compute() and returns
     * its result, then return it as a MethodNode for the emitter input. */
    private static MethodNode staticSynthetic(ClassNode container, String name, String desc) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, desc, null, null);
        mn.visitCode();
        // call into a mod-private static and return its Object — gives us a non-trivial body
        // for the embedder to copy over.
        mn.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/mod/MyMod", "compute",
                "()Ljava/lang/Object;", false);
        mn.visitInsn(Opcodes.ARETURN);
        mn.visitMaxs(1, Type.getArgumentTypes(desc).length);
        mn.visitEnd();
        container.methods.add(mn);
        return mn;
    }

    private record Result(LambdaWrapperEmitter.Artifacts art) { }
}

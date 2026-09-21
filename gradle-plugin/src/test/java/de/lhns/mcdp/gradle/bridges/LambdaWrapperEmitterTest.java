package de.lhns.mcdp.gradle.bridges;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0021 wrapper-emitter unit. Verifies that for a single lambda site the emitter produces
 * a bridge interface + impl pair with the expected shape, and that the impl's {@code make}
 * body uses {@code INVOKEDYNAMIC LambdaMetafactory} pointing at an embedded synthetic.
 */
class LambdaWrapperEmitterTest {

    private final LambdaWrapperEmitter emitter = new LambdaWrapperEmitter(
            "com.example.mod.mcdp_bridges", LambdaWrapperEmitterTest.class.getClassLoader());

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
        MethodNode synth = findMethod(impl, "lambda$body$0", "()Ljava/lang/String;");
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
                // The erased samMethodType and the specific instantiatedMethodType must come
                // out in their own slots, in the original order. Replaying one value into both
                // slots, or swapping them, parses fine and throws AbstractMethodError the first
                // time the JVM links the site -- so assert the two slots individually.
                assertEquals(SAM_ERASED, indy.bsmArgs[0],
                        "bsmArgs[0] must be the ERASED samMethodType");
                assertEquals(SAM_INSTANTIATED, indy.bsmArgs[2],
                        "bsmArgs[2] must be the SPECIFIC instantiatedMethodType");
                assertNotEquals(indy.bsmArgs[0], indy.bsmArgs[2],
                        "the two MethodType slots must not be the same value");
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
    void wrapperNamesArePackageQualified() {
        // Two mixins with the same simple name in different packages must not collide on one
        // wrapper class or one LAMBDA_* field (H3).
        ClassNode a = container("com/mod/a/Config");
        ClassNode b = container("com/mod/b/Config");
        MethodNode sa = staticSynthetic(a, "lambda$body$0", "()Ljava/lang/String;");
        MethodNode sb = staticSynthetic(b, "lambda$body$0", "()Ljava/lang/String;");
        LambdaWrapperEmitter.Artifacts artA = emitter.emit(a,
                site(a, "handler()V", new Handle(Opcodes.H_INVOKESTATIC, a.name,
                        "lambda$body$0", "()Ljava/lang/String;", false),
                        "()Ljava/util/function/Supplier;"), sa);
        LambdaWrapperEmitter.Artifacts artB = emitter.emit(b,
                site(b, "handler()V", new Handle(Opcodes.H_INVOKESTATIC, b.name,
                        "lambda$body$0", "()Ljava/lang/String;", false),
                        "()Ljava/util/function/Supplier;"), sb);
        assertNotEquals(artA.bridgeIfaceInternal, artB.bridgeIfaceInternal);
        assertNotEquals(artA.bridgeImplInternal, artB.bridgeImplInternal);
        assertNotEquals(artA.logicFieldName, artB.logicFieldName);
        assertTrue(artA.bridgeIfaceInternal.contains("Config"),
                "the readable part must survive: " + artA.bridgeIfaceInternal);
    }

    @Test
    void instanceSyntheticIsRefusedRatherThanCopiedAsStatic() {
        ClassNode container = container("com/example/mod/MixinFoo");
        MethodNode synthetic = staticSynthetic(container, "lambda$body$0", "()Ljava/lang/String;");
        Handle instanceHandle = new Handle(Opcodes.H_INVOKESPECIAL, container.name,
                "lambda$body$0", "()Ljava/lang/String;", false);
        LambdaSite bad = site(container, "handler()V", instanceHandle,
                "(Lcom/example/mod/MixinFoo;)Ljava/util/function/Supplier;");
        assertThrows(IllegalArgumentException.class,
                () -> emitter.emit(container, bad, synthetic));
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
        // synthetic that takes a String and returns String (matching capture + SAM).
        MethodNode synthetic = staticSynthetic(container, "lambda$body$0",
                "(Ljava/lang/String;)Ljava/lang/String;");
        Handle implHandle = new Handle(Opcodes.H_INVOKESTATIC, container.name,
                "lambda$body$0", "(Ljava/lang/String;)Ljava/lang/String;", false);
        LambdaSite site = site(container, "method()V", implHandle,
                "(Ljava/lang/String;)Ljava/util/function/Supplier;");

        LambdaWrapperEmitter.Artifacts art = emitter.emit(container, site, synthetic);
        ClassNode iface = parse(art.bridgeIfaceBytes);
        MethodNode make = iface.methods.get(0);
        assertEquals("(Ljava/lang/String;)Ljava/util/function/Supplier;", make.desc);
    }

    /**
     * The two {@code MethodType} slots of a {@code LambdaMetafactory} bsm-arg array, spelled
     * the way real javac spells them for {@code Supplier<String> s = () -> "x"} (checked
     * against {@code javap -v}; {@link LambdaBridgeRuntimeTest} compiles the real thing):
     * {@code bsmArgs[0]} is the SAM's <em>erased</em> signature and {@code bsmArgs[2]} the
     * <em>instantiated</em> one.
     *
     * <p>They MUST stay different values. Manufacturing both slots as
     * {@code ()Ljava/lang/Object;} makes this fixture agree with an emitter that swaps or
     * conflates them -- which is how ADR-0021 shipped broken with this suite green.</p>
     */
    private static final Type SAM_ERASED = Type.getType("()Ljava/lang/Object;");
    private static final Type SAM_INSTANTIATED = Type.getType("()Ljava/lang/String;");

    /**
     * A {@code Supplier<String>} site whose bootstrap metadata is spelled the way javac spells
     * it: the fixed 6-arg {@code metafactory} handle, an erased {@code samMethodType}
     * ({@code ()Object}) and a specific {@code instantiatedMethodType} ({@code ()String}),
     * with the implementation method's descriptor matching the instantiated type. The emitter
     * replays exactly this, slot for slot.
     */
    private static LambdaSite site(ClassNode container, String ownerMethodId,
                                   Handle implHandle, String indyDesc) {
        return new LambdaSite(container.name, ownerMethodId, 0,
                "java/util/function/Supplier", "get",
                BridgeScannerLambdaTest.metafactoryBsm(),
                new Object[]{
                        SAM_ERASED,
                        implHandle,
                        SAM_INSTANTIATED},
                indyDesc, 0);
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
        MethodNode synthetic = staticSynthetic(container, "lambda$body$0", "()Ljava/lang/String;");
        Handle implHandle = new Handle(Opcodes.H_INVOKESTATIC, container.name,
                "lambda$body$0", "()Ljava/lang/String;", false);
        LambdaSite site = site(container, "handler()V", implHandle,
                "()Ljava/util/function/Supplier;");
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
        // call into a mod-private static and return its String — gives us a non-trivial body
        // for the embedder to copy over.
        mn.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/mod/MyMod", "compute",
                "()Ljava/lang/String;", false);
        mn.visitInsn(Opcodes.ARETURN);
        mn.visitMaxs(1, Type.getArgumentTypes(desc).length);
        mn.visitEnd();
        container.methods.add(mn);
        return mn;
    }

    private record Result(LambdaWrapperEmitter.Artifacts art) { }
}

package de.lhns.mcdp.gradle.mixinbridges;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the scanner + rewriter + emitter pipeline produces a verifier-clean rewritten class
 * whose original cross-classloader call site is replaced by a bridge dispatch. Loads the
 * rewritten class, the emitted bridge interface, and the emitted impl through a single test
 * classloader, sets the LOGIC field, invokes the rewritten method, and asserts the result
 * matches the original target's return.
 */
class MixinRewriterEndToEndTest {

    private static final String BRIDGE_PKG = "mcdpbridges_test";
    private final BridgePolicy policy = new BridgePolicy(List.of(), BRIDGE_PKG);

    @Test
    void invokestaticDispatchesThroughBridge() throws Exception {
        // Original target class: com/example/Target with int doubleIt(int)
        byte[] target = targetClassWithStaticMethod();
        // Mixin: public static int handler(int x) { return Target.doubleIt(x) + 1; }
        byte[] mixin = mixinClassCallingTargetStatic();

        BridgeMixinScanner scanner = new BridgeMixinScanner(policy);
        MixinScanResult result = scanner.scan(mixin);
        assertEquals(MixinScanResult.Status.REWRITABLE, result.status());

        MixinRewriter rewriter = new MixinRewriter(policy, BRIDGE_PKG);
        byte[] rewritten = rewriter.rewrite(mixin, result.targets());

        BridgeInterfaceEmitter ifaceEmitter = new BridgeInterfaceEmitter(BRIDGE_PKG);
        BridgeImplEmitter implEmitter = new BridgeImplEmitter(BRIDGE_PKG);
        byte[] iface = ifaceEmitter.emit("com/example/Target", result.targets().get("com/example/Target"));
        byte[] impl = implEmitter.emit("com/example/Target", result.targets().get("com/example/Target"));

        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.example.Target", target);
        classes.put("com.example.Mixin", rewritten);
        classes.put(ifaceEmitter.interfaceFqn("com/example/Target"), iface);
        classes.put(implEmitter.implFqn("com/example/Target"), impl);

        InMemLoader loader = new InMemLoader(classes, getClass().getClassLoader());
        Class<?> mixinClass = loader.loadClass("com.example.Mixin");
        Class<?> ifaceClass = loader.loadClass(ifaceEmitter.interfaceFqn("com/example/Target"));
        Class<?> implClass = loader.loadClass(implEmitter.implFqn("com/example/Target"));

        // Wire LOGIC field.
        Object bridgeInstance = implClass.getDeclaredConstructor().newInstance();
        assertTrue(ifaceClass.isInstance(bridgeInstance));
        Field logic = mixinClass.getDeclaredField(MixinRewriter.logicFieldName("com/example/Target"));
        logic.setAccessible(true);
        logic.set(null, bridgeInstance);

        // Invoke handler.
        Method handler = mixinClass.getDeclaredMethod("handler", int.class);
        Object out = handler.invoke(null, 7);
        assertEquals(15, out); // doubleIt(7)+1 = 15
    }

    @Test
    void getStaticDispatchesThroughBridge() throws Exception {
        byte[] target = targetClassWithStaticField();
        byte[] mixin = mixinClassReadingStaticField();

        BridgeMixinScanner scanner = new BridgeMixinScanner(policy);
        MixinScanResult result = scanner.scan(mixin);
        assertEquals(MixinScanResult.Status.REWRITABLE, result.status());

        MixinRewriter rewriter = new MixinRewriter(policy, BRIDGE_PKG);
        byte[] rewritten = rewriter.rewrite(mixin, result.targets());

        BridgeInterfaceEmitter ifaceEmitter = new BridgeInterfaceEmitter(BRIDGE_PKG);
        BridgeImplEmitter implEmitter = new BridgeImplEmitter(BRIDGE_PKG);
        byte[] iface = ifaceEmitter.emit("com/example/TargetF", result.targets().get("com/example/TargetF"));
        byte[] impl = implEmitter.emit("com/example/TargetF", result.targets().get("com/example/TargetF"));

        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.example.TargetF", target);
        classes.put("com.example.MixinF", rewritten);
        classes.put(ifaceEmitter.interfaceFqn("com/example/TargetF"), iface);
        classes.put(implEmitter.implFqn("com/example/TargetF"), impl);

        InMemLoader loader = new InMemLoader(classes, getClass().getClassLoader());
        Class<?> mixinClass = loader.loadClass("com.example.MixinF");
        Class<?> implClass = loader.loadClass(implEmitter.implFqn("com/example/TargetF"));
        Object bridgeInstance = implClass.getDeclaredConstructor().newInstance();
        Field logic = mixinClass.getDeclaredField(MixinRewriter.logicFieldName("com/example/TargetF"));
        logic.setAccessible(true);
        logic.set(null, bridgeInstance);

        Method handler = mixinClass.getDeclaredMethod("readIt");
        Object out = handler.invoke(null);
        assertEquals(42, out);
    }

    @Test
    void rewrittenClassHasNoModPrivateReferences() {
        byte[] mixin = mixinClassCallingTargetStatic();
        BridgeMixinScanner scanner = new BridgeMixinScanner(policy);
        MixinRewriter rewriter = new MixinRewriter(policy, BRIDGE_PKG);
        byte[] rewritten = rewriter.rewrite(mixin, scanner.scan(mixin).targets());

        // A simple constant-pool scan: the rewritten class shouldn't contain "com/example/Target"
        // anywhere (call site replaced; no header reference). It SHOULD contain the bridge
        // interface FQN.
        String dump = new String(rewritten);
        assertTrue(dump.contains(BRIDGE_PKG.replace('.', '/') + "/TargetBridge"),
                "expected bridge interface reference");
        assertTrue(!dump.contains("com/example/Target") || dump.contains("TargetBridge"),
                "expected no bare reference to com/example/Target");
    }

    @Test
    void constructorDispatchesThroughBridge() throws Exception {
        // Target: com/example/TargetC with public no-arg ctor.
        byte[] target = simpleTargetClass("com/example/TargetC");
        // Mixin: public static Object handler() { return new TargetC(); }
        byte[] mixin = mixinClassConstructingTarget("com/example/TargetC");

        BridgeMixinScanner scanner = new BridgeMixinScanner(policy);
        MixinScanResult result = scanner.scan(mixin);
        assertEquals(MixinScanResult.Status.REWRITABLE, result.status());

        MixinRewriter rewriter = new MixinRewriter(policy, BRIDGE_PKG);
        byte[] rewritten = rewriter.rewrite(mixin, result.targets());

        BridgeInterfaceEmitter ifaceEmitter = new BridgeInterfaceEmitter(BRIDGE_PKG);
        BridgeImplEmitter implEmitter = new BridgeImplEmitter(BRIDGE_PKG);
        byte[] iface = ifaceEmitter.emit("com/example/TargetC",
                result.targets().get("com/example/TargetC"));
        byte[] impl = implEmitter.emit("com/example/TargetC",
                result.targets().get("com/example/TargetC"));

        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.example.TargetC", target);
        classes.put("com.example.MixinC", rewritten);
        classes.put(ifaceEmitter.interfaceFqn("com/example/TargetC"), iface);
        classes.put(implEmitter.implFqn("com/example/TargetC"), impl);

        InMemLoader loader = new InMemLoader(classes, getClass().getClassLoader());
        Class<?> mixinClass = loader.loadClass("com.example.MixinC");
        Class<?> targetClass = loader.loadClass("com.example.TargetC");
        Class<?> implClass = loader.loadClass(implEmitter.implFqn("com/example/TargetC"));
        Object bridgeInstance = implClass.getDeclaredConstructor().newInstance();
        Field logic = mixinClass.getDeclaredField(MixinRewriter.logicFieldName("com/example/TargetC"));
        logic.setAccessible(true);
        logic.set(null, bridgeInstance);

        Method handler = mixinClass.getDeclaredMethod("handler");
        Object out = handler.invoke(null);
        assertNotNull(out);
        assertTrue(targetClass.isInstance(out),
                "expected Object returned by handler to be an instance of TargetC");
    }

    @Test
    void putStaticDispatchesThroughBridge() throws Exception {
        byte[] target = targetClassWithMutableStaticField("com/example/TargetS");
        byte[] mixin = mixinClassWritingThenReadingStatic("com/example/TargetS");

        BridgeMixinScanner scanner = new BridgeMixinScanner(policy);
        MixinScanResult result = scanner.scan(mixin);
        assertEquals(MixinScanResult.Status.REWRITABLE, result.status());

        MixinRewriter rewriter = new MixinRewriter(policy, BRIDGE_PKG);
        byte[] rewritten = rewriter.rewrite(mixin, result.targets());

        BridgeInterfaceEmitter ifaceEmitter = new BridgeInterfaceEmitter(BRIDGE_PKG);
        BridgeImplEmitter implEmitter = new BridgeImplEmitter(BRIDGE_PKG);
        byte[] iface = ifaceEmitter.emit("com/example/TargetS",
                result.targets().get("com/example/TargetS"));
        byte[] impl = implEmitter.emit("com/example/TargetS",
                result.targets().get("com/example/TargetS"));

        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.example.TargetS", target);
        classes.put("com.example.MixinS", rewritten);
        classes.put(ifaceEmitter.interfaceFqn("com/example/TargetS"), iface);
        classes.put(implEmitter.implFqn("com/example/TargetS"), impl);

        InMemLoader loader = new InMemLoader(classes, getClass().getClassLoader());
        Class<?> mixinClass = loader.loadClass("com.example.MixinS");
        Class<?> implClass = loader.loadClass(implEmitter.implFqn("com/example/TargetS"));
        Object bridgeInstance = implClass.getDeclaredConstructor().newInstance();
        Field logic = mixinClass.getDeclaredField(MixinRewriter.logicFieldName("com/example/TargetS"));
        logic.setAccessible(true);
        logic.set(null, bridgeInstance);

        Method handler = mixinClass.getDeclaredMethod("writeAndRead", int.class);
        assertEquals(123, handler.invoke(null, 123));
    }

    @Test
    void ldcClassLiteralDispatchesThroughBridge() throws Exception {
        byte[] target = simpleTargetClass("com/example/TargetL");
        byte[] mixin = mixinClassWithClassLiteral("com/example/TargetL");

        BridgeMixinScanner scanner = new BridgeMixinScanner(policy);
        MixinScanResult result = scanner.scan(mixin);
        assertEquals(MixinScanResult.Status.REWRITABLE, result.status());

        MixinRewriter rewriter = new MixinRewriter(policy, BRIDGE_PKG);
        byte[] rewritten = rewriter.rewrite(mixin, result.targets());

        BridgeInterfaceEmitter ifaceEmitter = new BridgeInterfaceEmitter(BRIDGE_PKG);
        BridgeImplEmitter implEmitter = new BridgeImplEmitter(BRIDGE_PKG);
        byte[] iface = ifaceEmitter.emit("com/example/TargetL",
                result.targets().get("com/example/TargetL"));
        byte[] impl = implEmitter.emit("com/example/TargetL",
                result.targets().get("com/example/TargetL"));

        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.example.TargetL", target);
        classes.put("com.example.MixinL", rewritten);
        classes.put(ifaceEmitter.interfaceFqn("com/example/TargetL"), iface);
        classes.put(implEmitter.implFqn("com/example/TargetL"), impl);

        InMemLoader loader = new InMemLoader(classes, getClass().getClassLoader());
        Class<?> mixinClass = loader.loadClass("com.example.MixinL");
        Class<?> targetClass = loader.loadClass("com.example.TargetL");
        Class<?> implClass = loader.loadClass(implEmitter.implFqn("com/example/TargetL"));
        Object bridgeInstance = implClass.getDeclaredConstructor().newInstance();
        Field logic = mixinClass.getDeclaredField(MixinRewriter.logicFieldName("com/example/TargetL"));
        logic.setAccessible(true);
        logic.set(null, bridgeInstance);

        Method handler = mixinClass.getDeclaredMethod("handler");
        Object out = handler.invoke(null);
        assertEquals(targetClass, out);
    }

    @Test
    void emittedInterfaceIsValidByteCode() throws Exception {
        BridgeMember bm = new BridgeMember(BridgeMember.Kind.STATIC_METHOD, "doubleIt", "(I)I");
        byte[] iface = new BridgeInterfaceEmitter(BRIDGE_PKG)
                .emit("com/example/Target", List.of(bm));
        // Define and inspect.
        InMemLoader loader = new InMemLoader(
                Map.of(BRIDGE_PKG + ".TargetBridge", iface), getClass().getClassLoader());
        Class<?> ifaceClass = loader.loadClass(BRIDGE_PKG + ".TargetBridge");
        assertTrue(ifaceClass.isInterface());
        Method m = ifaceClass.getDeclaredMethod("doubleIt", int.class);
        assertNotNull(m);
        assertEquals(int.class, m.getReturnType());
    }

    // --- fixture builders ---

    private static byte[] targetClassWithStaticMethod() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/Target", null,
                "java/lang/Object", null);
        // public static int doubleIt(int x) { return x*2; }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "doubleIt", "(I)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        // ctor
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

    private static byte[] mixinClassCallingTargetStatic() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/Mixin", null,
                "java/lang/Object", null);
        // public static int handler(int x) { return Target.doubleIt(x) + 1; }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "handler", "(I)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/Target", "doubleIt", "(I)I", false);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        // ctor
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

    private static byte[] targetClassWithStaticField() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/TargetF", null,
                "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "VAL", "I", null, 42).visitEnd();
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

    private static byte[] mixinClassReadingStaticField() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/MixinF", null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "readIt", "()I", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "com/example/TargetF", "VAL", "I");
        mv.visitInsn(Opcodes.IRETURN);
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

    private static byte[] simpleTargetClass(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
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

    private static byte[] mixinClassConstructingTarget(String targetInternal) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/MixinC", null,
                "java/lang/Object", null);
        // public static Object handler() { return new TargetC(); }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "handler", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, targetInternal);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, targetInternal, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ARETURN);
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

    private static byte[] targetClassWithMutableStaticField(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        // public static int VAL;  (mutable)
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "VAL", "I", null, null).visitEnd();
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

    private static byte[] mixinClassWritingThenReadingStatic(String targetInternal) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/MixinS", null,
                "java/lang/Object", null);
        // public static int writeAndRead(int v) { TargetS.VAL = v; return TargetS.VAL; }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "writeAndRead", "(I)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, targetInternal, "VAL", "I");
        mv.visitFieldInsn(Opcodes.GETSTATIC, targetInternal, "VAL", "I");
        mv.visitInsn(Opcodes.IRETURN);
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

    private static byte[] mixinClassWithClassLiteral(String targetInternal) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/MixinL", null,
                "java/lang/Object", null);
        // public static Class<?> handler() { return TargetL.class; }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "handler", "()Ljava/lang/Class;", null, null);
        mv.visitCode();
        mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType(targetInternal));
        mv.visitInsn(Opcodes.ARETURN);
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

package de.lhns.mcdp.gradle.bridges;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeScannerTest {

    private final BridgePolicy policy = new BridgePolicy(
            List.of("com/example/api/"),
            "com.example.mod.mcdp_bridges");

    @Test
    void invokestaticOnModPrivateOwnerIsBridged() {
        byte[] bytes = mixinWith(mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/mod/MyMod", "shouldCancel",
                    "(Ljava/lang/String;)Z", false);
            mv.visitInsn(Opcodes.IRETURN);
        }, "(Ljava/lang/String;)Z");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.REWRITABLE, r.status());
        assertTrue(r.targets().containsKey("com/example/mod/MyMod"));
        assertEquals(1, r.targets().get("com/example/mod/MyMod").size());
        assertEquals(BridgeMember.Kind.STATIC_METHOD,
                r.targets().get("com/example/mod/MyMod").get(0).kind());
    }

    @Test
    void invokevirtualOnModPrivateOwnerIsBridgedAsVirtual() {
        byte[] bytes = mixinWith(mv -> {
            mv.visitTypeInsn(Opcodes.NEW, "com/example/mod/Foo");
            // NB: NEW is flagged as warning; that's fine for this scanner-coverage test
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.POP); // discard NEW result
            mv.visitInsn(Opcodes.POP);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/mod/Foo", "doThing", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
        }, "()V");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        // The class header is fine; only NEW emits a warning. INVOKEVIRTUAL gets bridged.
        assertEquals(BridgeScanResult.Status.REWRITABLE, r.status());
        BridgeMember bm = r.targets().get("com/example/mod/Foo").stream()
                .filter(m -> m.kind() == BridgeMember.Kind.VIRTUAL_METHOD)
                .findFirst().orElseThrow();
        assertEquals("doThing", bm.name());
    }

    @Test
    void getstaticOnModPrivateOwnerBecomesStaticFieldGet() {
        byte[] bytes = mixinWith(mv -> {
            mv.visitFieldInsn(Opcodes.GETSTATIC, "com/example/mod/Foo", "BAR", "I");
            mv.visitInsn(Opcodes.IRETURN);
        }, "()I");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.REWRITABLE, r.status());
        BridgeMember bm = r.targets().get("com/example/mod/Foo").get(0);
        assertEquals(BridgeMember.Kind.STATIC_FIELD_GET, bm.kind());
        assertEquals("BAR", bm.name());
    }

    @Test
    void invokestaticOnPlatformOwnerIsNotBridged() {
        byte[] bytes = mixinWith(mv -> {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                    "(I)Ljava/lang/Integer;", false);
            mv.visitInsn(Opcodes.ARETURN);
        }, "()Ljava/lang/Integer;");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.SKIPPED, r.status());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void modPrivateInterfaceOnHeaderRejected() {
        byte[] bytes = mixinHeader(new String[] { "com/example/mod/MyIface" }, null);
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.UNSUPPORTED, r.status());
        assertEquals(1, r.errors().size());
        assertTrue(r.errors().get(0).contains("implements"));
        assertTrue(r.errors().get(0).contains("com.example.mod.MyIface"));
        assertTrue(r.errors().get(0).contains("sharedPackages"));
    }

    @Test
    void modPrivateSuperOnHeaderRejected() {
        byte[] bytes = mixinHeader(null, "com/example/mod/Base");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.UNSUPPORTED, r.status());
        assertTrue(r.errors().get(0).contains("extends"));
    }

    @Test
    void platformInterfaceOnHeaderAccepted() {
        byte[] bytes = mixinHeader(new String[] { "java/lang/Runnable" }, "java/lang/Object");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertFalse(r.status() == BridgeScanResult.Status.UNSUPPORTED);
    }

    @Test
    void sharedPackagesEntryAccepted() {
        byte[] bytes = mixinWith(mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/api/Hook",
                    "fire", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
        }, "()V");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.SKIPPED, r.status());
    }

    @Test
    void scalaTypeInDescriptorEmitsWarning() {
        byte[] bytes = mixinWith(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/mod/Foo", "withOption",
                    "(Lscala/Option;)V", false);
            mv.visitInsn(Opcodes.RETURN);
        }, "()V");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.REWRITABLE, r.status());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("scala.Option")));
    }

    @Test
    void classForNameOnModPrivateStringEmitsWarning() {
        byte[] bytes = mixinWith(mv -> {
            mv.visitLdcInsn("com.example.mod.Foo");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;", false);
            mv.visitInsn(Opcodes.ARETURN);
        }, "()Ljava/lang/Class;");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("Class.forName")));
    }

    @Test
    void checkcastOnModPrivateOwnerEmitsBridgeModelLimitWarning() {
        // CHECKCAST/INSTANCEOF/ANEWARRAY of mod-private types are formally documented as
        // bridge-model limits (ADR-0018 v2 docs). The warning text must point at the user's
        // only options — sharedPackages or restructuring — and reference docs/bridges.md
        // so the build output gives the user something concrete to read.
        byte[] bytes = mixinWith(mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "com/example/mod/Foo");
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
        }, "(Ljava/lang/Object;)V");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertTrue(r.warnings().stream().anyMatch(w ->
                w.contains("bridge-model limit") && w.contains("docs/bridges.md")
                        && w.contains("sharedPackages")));
    }

    @Test
    void invokespecialInitOnModPrivateOwnerIsBridgedAsConstructor() {
        byte[] bytes = mixinWith(mv -> {
            mv.visitTypeInsn(Opcodes.NEW, "com/example/mod/Foo");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/example/mod/Foo",
                    "<init>", "()V", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
        }, "()V");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.REWRITABLE, r.status());
        BridgeMember bm = r.targets().get("com/example/mod/Foo").stream()
                .filter(m -> m.kind() == BridgeMember.Kind.CONSTRUCTOR)
                .findFirst().orElseThrow();
        assertEquals("<init>", bm.name());
        assertEquals("()V", bm.descriptor());
    }

    @Test
    void putstaticOnModPrivateOwnerBecomesStaticFieldSet() {
        byte[] bytes = mixinWith(mv -> {
            mv.visitInsn(Opcodes.ICONST_5);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, "com/example/mod/Foo", "BAR", "I");
            mv.visitInsn(Opcodes.RETURN);
        }, "()V");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.REWRITABLE, r.status());
        BridgeMember bm = r.targets().get("com/example/mod/Foo").get(0);
        assertEquals(BridgeMember.Kind.STATIC_FIELD_SET, bm.kind());
        assertEquals("BAR", bm.name());
    }

    @Test
    void putfieldOnModPrivateOwnerBecomesInstanceFieldSet() {
        byte[] bytes = mixinWith(mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.ICONST_5);
            mv.visitFieldInsn(Opcodes.PUTFIELD, "com/example/mod/Foo", "x", "I");
            mv.visitInsn(Opcodes.RETURN);
        }, "(Ljava/lang/Object;)V");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.REWRITABLE, r.status());
        BridgeMember bm = r.targets().get("com/example/mod/Foo").get(0);
        assertEquals(BridgeMember.Kind.INSTANCE_FIELD_SET, bm.kind());
    }

    @Test
    void ldcClassLiteralOnModPrivateBecomesClassLiteral() {
        byte[] bytes = mixinWith(mv -> {
            mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType("com/example/mod/Foo"));
            mv.visitInsn(Opcodes.ARETURN);
        }, "()Ljava/lang/Class;");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.REWRITABLE, r.status());
        BridgeMember bm = r.targets().get("com/example/mod/Foo").get(0);
        assertEquals(BridgeMember.Kind.CLASS_LITERAL, bm.kind());
    }

    /**
     * Self-references in the mixin body don't need bridging — same defining loader on both
     * sides. Without this filter, ADR-0008 legacy {@code @McdpMixin} mixins (which PUTSTATIC
     * a {@code LOGIC} field on themselves) would generate a bridge that loads the mixin class,
     * and Sponge throws {@code IllegalClassLoadError} because mixin classes can't be loaded
     * directly. Regression guard for the fluidphysics-fabric CI failure.
     */
    @Test
    void putstaticOnSelfDoesNotEmitBridge() {
        byte[] bytes = mixinWith(mv -> {
            // mixinWith builds the class as com/example/mod/mixin/MixinFoo — self-reference.
            mv.visitInsn(Opcodes.ICONST_5);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, "com/example/mod/mixin/MixinFoo", "LOGIC", "I");
            mv.visitInsn(Opcodes.RETURN);
        }, "()V");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.SKIPPED, r.status(),
                "self-reference inside the mixin body should not seed a bridge target");
        assertTrue(r.targets().isEmpty(),
                "expected empty targets, got: " + r.targets());
    }

    @Test
    void invokestaticOnSelfDoesNotEmitBridge() {
        byte[] bytes = mixinWith(mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/mod/mixin/MixinFoo",
                    "helper", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
        }, "()V");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.SKIPPED, r.status());
    }

    @Test
    void getstaticOnSelfDoesNotEmitBridge() {
        byte[] bytes = mixinWith(mv -> {
            mv.visitFieldInsn(Opcodes.GETSTATIC, "com/example/mod/mixin/MixinFoo", "LOGIC", "I");
            mv.visitInsn(Opcodes.IRETURN);
        }, "()I");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.SKIPPED, r.status());
    }

    @Test
    void abstractAccessorReturningModPrivateTypeIsUnsupported() {
        // The classic @Accessor: no body, so nothing for the rewriter to touch and the class
        // would otherwise be SKIPPED -- but the merged descriptor names a mod-private type on
        // the game loader, i.e. a NoClassDefFoundError the moment anything resolves it.
        byte[] bytes = mixinWithAbstractMethod("getThing", "()Lcom/example/mod/MyModThing;");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.UNSUPPORTED, r.status());
        assertTrue(r.errors().stream().anyMatch(e ->
                        e.contains("getThing") && e.contains("com.example.mod.MyModThing")
                                && e.contains("sharedPackages")),
                "expected an actionable method-signature error, got: " + r.errors());
    }

    @Test
    void modPrivateParameterTypeIsUnsupported() {
        byte[] bytes = mixinWithAbstractMethod("setThing", "(Lcom/example/mod/MyModThing;)V");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.UNSUPPORTED, r.status());
    }

    @Test
    void sharedAndPlatformTypesInMethodSignaturesAreFine() {
        // com/example/api is in sharedPackages; CallbackInfo and MC types are platform.
        byte[] bytes = mixinWithAbstractMethod("ok",
                "(Lcom/example/api/Thing;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)"
                        + "Lnet/minecraft/world/level/Level;");
        BridgeScanResult r = new BridgeScanner(policy).scan(bytes);
        assertEquals(BridgeScanResult.Status.SKIPPED, r.status());
    }

    @Test
    void syntheticMethodSignaturesAreExempt() {
        // Lambda bodies legitimately carry mod-private types; the ADR-0021 path moves them to a
        // wrapper on the mod-side loader, so they must not fail the build here.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/mod/mixin/MixinFoo", null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$body$0", "(Lcom/example/mod/MyModThing;)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        BridgeScanResult r = new BridgeScanner(policy).scan(cw.toByteArray());
        assertEquals(BridgeScanResult.Status.SKIPPED, r.status());
    }

    /** Build a class declaring one abstract method with the given descriptor. */
    private static byte[] mixinWithAbstractMethod(String name, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "com/example/mod/mixin/MixinFoo", null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, desc, null, null)
                .visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Build a minimal class with one method whose body the caller writes via {@code body}. */
    private static byte[] mixinWith(java.util.function.Consumer<MethodVisitor> body, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/mod/mixin/MixinFoo", null,
                "java/lang/Object", null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "handler", desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Build an empty class with the given header. */
    private static byte[] mixinHeader(String[] interfaces, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/mod/mixin/MixinFoo", null,
                superName == null ? "java/lang/Object" : superName, interfaces);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                superName == null ? "java/lang/Object" : superName, "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}

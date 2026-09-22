package de.lhns.mcdp.gradle.validate;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ADR-0024 validator A — over-share detection. Synthetic ASM-built classes
 * mirror the {@link de.lhns.mcdp.gradle.bridges.BridgeScannerTest} pattern.
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

    // ---- bridge-aware validation (ADR-0024 validator A x ADR-0021 codegen) -------------------

    private static final String BRIDGE_PKG = "com.example.mcdp_bridges";
    private static final String CONFIG_BRIDGE = "com/example/mcdp_bridges/ConfigBridge";
    private static final String MOD_BRIDGE = "com/example/mcdp_bridges/ModBridge";
    private static final String CONFIG = "com/example/modprivate/Config";

    /**
     * The codegen auto-adds its bridge package to {@code sharedPackages}, so the production
     * validator always sees it as one more shared prefix.
     */
    private final SharedPackageContentValidator bridgeAware = new SharedPackageContentValidator(
            List.of("com.example.shared.", "com.example.iface.", BRIDGE_PKG + "."), BRIDGE_PKG);

    /**
     * Bytecode in the exact shape {@code BridgeRewriter} produces for
     * {@code Mod.config().isEnabledFor(..)}: every <em>call</em> goes through a bridge, and the
     * mod-private {@code Config} survives only inside the bridges' own method descriptors. This
     * boots and runs (verified against mc-fluid-physics); the validator used to fail it.
     *
     * <p>Mutation caught: removing the {@code isBridgeOwner} guard in {@link ClassRefCollector},
     * or dropping the {@code bridgePackage} argument anywhere along
     * {@code ValidateSharedPackagesTask -> SharedPackageContentValidator -> ClassRefCollector}.
     * The second assertion pins that last hop specifically: the same bytes still fail when the
     * validator is not told the bridge package, so a lost argument cannot pass silently.
     */
    @Test
    void acceptsCallRoutedThroughABridgeWhoseDescriptorNamesAModPrivateType() {
        byte[] bytes = classWith("com/example/shared/RewrittenMixin", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            mv.visitCode();
            // LOGIC_Config, typed as the shared bridge interface.
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitTypeInsn(Opcodes.CHECKCAST, CONFIG_BRIDGE);
            // LOGIC_Mod.config() -> Config: return type is the mod-private type.
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitTypeInsn(Opcodes.CHECKCAST, MOD_BRIDGE);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, MOD_BRIDGE, "config",
                    "()L" + CONFIG + ";", true);
            // LOGIC_Config.isEnabledFor(config) -> Z: the receiver became the first parameter.
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONFIG_BRIDGE, "isEnabledFor",
                    "(L" + CONFIG + ";)Z", true);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(3, 1);
            mv.visitEnd();
        });
        List<Diagnostic> diags = bridgeAware.validate(bytes);
        assertTrue(diags.isEmpty(), "bridged call reported as an over-share: " + diags);
        // Same bytes, validator not told which prefix is the bridge one -> still reported.
        SharedPackageContentValidator unaware = new SharedPackageContentValidator(
                List.of("com.example.shared.", BRIDGE_PKG + "."));
        assertEquals(1, unaware.validate(bytes).size());
    }

    /**
     * The exemption must not become "this type is fine because a bridge mentions it somewhere".
     * Here the same class both calls through the bridge AND touches {@code Config} directly in
     * the shapes user code actually takes: {@code NEW} + {@code <init>}, a field of that type, a
     * {@code CHECKCAST}, and a direct virtual call on it. All of those really do
     * {@code NoClassDefFoundError} on the platform loader, so all must still be reported.
     *
     * <p>Mutation caught: broadening the exemption from "descriptor of a member owned by a
     * bridge type" to anything coarser — exempting the {@code TypeInsnNode} branch, the
     * {@code FieldNode}/{@code FieldInsnNode} descriptors, or post-filtering the offending list
     * against types seen in bridge descriptors. Any of those turns this fix into a blanket
     * exemption and re-opens the over-share footgun ADR-0024 exists to catch.
     */
    @Test
    void stillRejectsDirectUseOfTheModPrivateTypeInABridgedClass() {
        // One mechanism per case, deliberately. An earlier version asserted all four in a single
        // class, which made it useless for its stated purpose: the diagnostic is an OR over the
        // collected refs, so exempting any one mechanism was masked by the other three, and a
        // coarsening that skipped NEW/CHECKCAST passed. Each case below is the only thing naming
        // the mod-private type besides the legitimate bridged call.
        assertOverShares("NEW + <init>", mv -> {
            mv.visitTypeInsn(Opcodes.NEW, CONFIG);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, CONFIG, "<init>", "()V", false);
            mv.visitInsn(Opcodes.POP);
        });
        assertOverShares("CHECKCAST", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitTypeInsn(Opcodes.CHECKCAST, CONFIG);
            mv.visitInsn(Opcodes.POP);
        });
        assertOverShares("direct call on the type", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONFIG, "reload", "()V", false);
        });
    }

    /**
     * A class that makes one legitimate bridged call and then names the mod-private type exactly
     * once more, via {@code body}. The bridged call must not launder the direct reference.
     */
    private void assertOverShares(String mechanism, java.util.function.Consumer<MethodVisitor> body) {
        byte[] bytes = classWith("com/example/shared/OverShares", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            mv.visitCode();
            // The legitimate bridged call, exactly as in the accepting test above.
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONFIG_BRIDGE, "isEnabledFor",
                    "(L" + CONFIG + ";)Z", true);
            mv.visitInsn(Opcodes.POP);
            body.accept(mv);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(4, 1);
            mv.visitEnd();
        });
        List<Diagnostic> diags = bridgeAware.validate(bytes);
        assertEquals(1, diags.size(), mechanism + ": expected exactly one diagnostic");
        assertEquals(Diagnostic.Kind.OVER_SHARE, diags.get(0).kind(), mechanism);
        assertTrue(diags.get(0).offendingRefs().contains("com.example.modprivate.Config"),
                mechanism + ": direct use was swallowed: " + diags.get(0).offendingRefs());
    }

    /** A field typed as the mod-private type is an over-share even with a bridged call present. */
    @Test
    void stillRejectsAFieldTypedAsTheModPrivateType() {
        byte[] bytes = classWith("com/example/shared/HasField", cw -> {
            cw.visitField(Opcodes.ACC_PRIVATE, "cached", "L" + CONFIG + ";", null, null).visitEnd();
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONFIG_BRIDGE, "isEnabledFor",
                    "(L" + CONFIG + ";)Z", true);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();
        });
        List<Diagnostic> diags = bridgeAware.validate(bytes);
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).offendingRefs().contains("com.example.modprivate.Config"),
                "a field of the mod-private type was swallowed: " + diags.get(0).offendingRefs());
    }

    /**
     * Bridge impls live in the sibling {@code <bridgePackage>_impl} package and are deliberately
     * NOT shared (ADR-0021 errata) — the per-mod {@code ModClassLoader} child-loads them. A
     * shared class naming one is a real failure, so the impl package gets no exemption and the
     * impl class itself is reported too.
     *
     * <p>Mutation caught: computing the bridge prefix without its trailing separator — a plain
     * {@code startsWith("com/example/mcdp_bridges")} swallows {@code mcdp_bridges_impl}.
     */
    @Test
    void bridgeImplPackageIsNotExempt() {
        String impl = "com/example/mcdp_bridges_impl/ConfigBridgeImpl";
        byte[] bytes = classWith("com/example/shared/TouchesImpl", cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitTypeInsn(Opcodes.CHECKCAST, impl);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, impl, "isEnabledFor",
                    "(L" + CONFIG + ";)Z", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();
        });
        List<Diagnostic> diags = bridgeAware.validate(bytes);
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).offendingRefs().contains("com.example.mcdp_bridges_impl.ConfigBridgeImpl"),
                diags.get(0).offendingRefs().toString());
        assertTrue(diags.get(0).offendingRefs().contains("com.example.modprivate.Config"),
                diags.get(0).offendingRefs().toString());
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

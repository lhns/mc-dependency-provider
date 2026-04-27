package de.lhns.mcdp.gradle.mixinbridges;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * Emits a bridge <em>interface</em> as bytecode (no Java source intermediary). Lives in the
 * codegen's bridge package, which is auto-added to {@code sharedPackages} so the per-mod
 * {@code ModClassLoader} delegates loading parent-first — the interface
 * resolves to the same {@code Class} on both sides of the loader split.
 *
 * <p>The interface is a pure declaration: every method is abstract and public, and the
 * constant pool holds only the bridge package, primitive types, the java.lang root, and the
 * target type's {@code internalName} (which is a Minecraft / platform / sharedPackages type
 * for the receiver-leading variant — but not a mod-private type, because the rewriter only
 * uses bridges for non-platform owners). The emitted class verifies cleanly under the
 * game-layer's class verifier.</p>
 */
public final class BridgeInterfaceEmitter {

    private final String bridgePackageInternal;

    public BridgeInterfaceEmitter(String bridgePackage) {
        this.bridgePackageInternal = BridgePolicy.toInternal(bridgePackage);
    }

    public byte[] emit(String targetInternalName, List<BridgeMember> members) {
        String simple = MixinRewriter.simpleName(targetInternalName);
        String ifaceInternal = bridgePackageInternal + "/" + simple + "Bridge";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                ifaceInternal,
                null,
                "java/lang/Object",
                null);
        for (BridgeMember bm : members) {
            String desc = bm.bridgeMethodDescriptor(targetInternalName);
            MethodVisitor mv = cw.visitMethod(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                    bm.bridgeMethodName(),
                    desc,
                    null,
                    null);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    public String interfaceFqn(String targetInternalName) {
        return BridgePolicy.toDotted(bridgePackageInternal) + "."
                + MixinRewriter.simpleName(targetInternalName) + "Bridge";
    }
}

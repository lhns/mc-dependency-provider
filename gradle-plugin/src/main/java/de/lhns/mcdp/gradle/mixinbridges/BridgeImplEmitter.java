package de.lhns.mcdp.gradle.mixinbridges;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;

/**
 * Emits the forwarding <em>impl</em> for a bridge interface. Each impl is a thin, stateless
 * forwarder: every bridge method body either invokes the static target method, virtual-dispatches
 * on the leading receiver argument, or reads the named field.
 *
 * <p>The impl class lives in the codegen's bridge package and ends up in the mod's main
 * classes output, so it's loaded through the per-mod {@code ModClassLoader}
 * — that's where mod-private types resolve. Bytecode references to the target are constant-
 * pool entries against the per-mod loader; they verify only when the impl is loaded under
 * that loader (which is exactly what happens at runtime).</p>
 */
public final class BridgeImplEmitter {

    private final String bridgePackageInternal;
    private final ClassLoader frameLookup;

    public BridgeImplEmitter(String bridgePackage) {
        this(bridgePackage, ClassLoader.getSystemClassLoader());
    }

    public BridgeImplEmitter(String bridgePackage, ClassLoader frameLookup) {
        this.bridgePackageInternal = BridgePolicy.toInternal(bridgePackage);
        this.frameLookup = frameLookup;
    }

    public byte[] emit(String targetInternalName, List<BridgeMember> members) {
        String simple = MixinRewriter.simpleName(targetInternalName);
        String ifaceInternal = bridgePackageInternal + "/" + simple + "Bridge";
        String implInternal = ifaceInternal + "Impl";

        ClassWriter cw = new ClasspathAwareClassWriter(
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES, frameLookup);
        cw.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                implInternal,
                null,
                "java/lang/Object",
                new String[] { ifaceInternal });

        // public no-arg ctor
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        for (BridgeMember bm : members) {
            emitForwarder(cw, targetInternalName, bm);
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private void emitForwarder(ClassWriter cw, String targetInternal, BridgeMember bm) {
        String bridgeDesc = bm.bridgeMethodDescriptor(targetInternal);
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC,
                bm.bridgeMethodName(),
                bridgeDesc,
                null,
                null);
        mv.visitCode();
        Type[] argTypes = Type.getArgumentTypes(bridgeDesc);
        Type retType = Type.getReturnType(bridgeDesc);
        switch (bm.kind()) {
            case STATIC_METHOD -> {
                int local = 1; // skip 'this'
                for (Type t : argTypes) {
                    mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), local);
                    local += t.getSize();
                }
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, targetInternal, bm.name(), bm.descriptor(), false);
                mv.visitInsn(retType.getOpcode(Opcodes.IRETURN));
            }
            case VIRTUAL_METHOD, INTERFACE_METHOD -> {
                // bridge desc: (Lowner;arg0;arg1;...)R
                int local = 1; // 'this'
                mv.visitVarInsn(Opcodes.ALOAD, local); // receiver (first param of bridge method)
                local += 1;
                for (int i = 1; i < argTypes.length; i++) {
                    Type t = argTypes[i];
                    mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), local);
                    local += t.getSize();
                }
                int origOp = bm.kind() == BridgeMember.Kind.INTERFACE_METHOD
                        ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                mv.visitMethodInsn(
                        origOp, targetInternal, bm.name(), bm.descriptor(),
                        bm.kind() == BridgeMember.Kind.INTERFACE_METHOD);
                mv.visitInsn(retType.getOpcode(Opcodes.IRETURN));
            }
            case STATIC_FIELD_GET -> {
                mv.visitFieldInsn(Opcodes.GETSTATIC, targetInternal, bm.name(), bm.descriptor());
                mv.visitInsn(retType.getOpcode(Opcodes.IRETURN));
            }
            case INSTANCE_FIELD_GET -> {
                mv.visitVarInsn(Opcodes.ALOAD, 1); // receiver
                mv.visitFieldInsn(Opcodes.GETFIELD, targetInternal, bm.name(), bm.descriptor());
                mv.visitInsn(retType.getOpcode(Opcodes.IRETURN));
            }
            case STATIC_FIELD_SET -> {
                Type fieldT = Type.getType(bm.descriptor());
                mv.visitVarInsn(fieldT.getOpcode(Opcodes.ILOAD), 1);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, targetInternal, bm.name(), bm.descriptor());
                mv.visitInsn(Opcodes.RETURN);
            }
            case INSTANCE_FIELD_SET -> {
                Type fieldT = Type.getType(bm.descriptor());
                mv.visitVarInsn(Opcodes.ALOAD, 1); // receiver
                mv.visitVarInsn(fieldT.getOpcode(Opcodes.ILOAD), 2);
                mv.visitFieldInsn(Opcodes.PUTFIELD, targetInternal, bm.name(), bm.descriptor());
                mv.visitInsn(Opcodes.RETURN);
            }
            case CONSTRUCTOR -> {
                // public Object newInstance(args) { return new T(args); }
                mv.visitTypeInsn(Opcodes.NEW, targetInternal);
                mv.visitInsn(Opcodes.DUP);
                int local = 1; // skip 'this'
                Type[] originalArgs = Type.getArgumentTypes(bm.descriptor());
                for (Type t : originalArgs) {
                    mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), local);
                    local += t.getSize();
                }
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, targetInternal,
                        "<init>", bm.descriptor(), false);
                mv.visitInsn(Opcodes.ARETURN);
            }
            case CLASS_LITERAL -> {
                mv.visitLdcInsn(Type.getObjectType(targetInternal));
                mv.visitInsn(Opcodes.ARETURN);
            }
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    public String implFqn(String targetInternalName) {
        return BridgePolicy.toDotted(bridgePackageInternal) + "."
                + MixinRewriter.simpleName(targetInternalName) + "BridgeImpl";
    }
}

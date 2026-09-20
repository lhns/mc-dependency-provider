package de.lhns.mcdp.gradle.bridges;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Emits the bridge interface + impl pair for one {@link LambdaSite} (ADR-0021).
 *
 * <p>Each lambda site gets:
 * <ul>
 *   <li>A <em>per-site bridge interface</em> with a single {@code make(captures...) -> SAM}
 *       factory method.</li>
 *   <li>A <em>per-site bridge impl</em> implementing that interface. The impl's {@code make}
 *       body uses {@code INVOKEDYNAMIC LambdaMetafactory} to construct the SAM instance,
 *       targeting a private-static synthetic copied from the original mixin. Since the impl
 *       class lives in the bridge package and is loaded by {@code ModClassLoader} at runtime
 *       (per the existing manifest-driven registration, ADR-0019), the synthetic body's
 *       Scala/Kotlin/mod-private references resolve correctly.</li>
 * </ul>
 *
 * <p>The original indy in the mixin is replaced by the rewriter with a stack-juggle that
 * pushes the {@code LAMBDA_*} static field (typed as the bridge interface) under the captures
 * and INVOKEINTERFACEs {@code make}. The mixin's bytecode no longer contains an
 * {@code INVOKEDYNAMIC LambdaMetafactory} pointing at a synthetic with mod-private
 * references — and that's what closes the post-Sponge-merge CNF.</p>
 *
 * <p><b>Limitation (v1):</b> capture types are passed through as-declared. If a capture is a
 * Scala/Kotlin type, the bridge interface descriptor names it, and game-layer-loaded code
 * referencing the bridge interface would fail to resolve. The bulk of real-world cases
 * capture Java/MC types and pass Scala values only inside the lambda body. Future work:
 * Object-erased capture passing with CHECKCAST insertion in the impl. See ADR-0021 §Out
 * of scope.</p>
 */
public final class LambdaWrapperEmitter {

    private final String bridgePackageInternal;
    /** Sibling-package for impls — see {@link BridgeImplEmitter}'s field of the same name. */
    private final String implPackageInternal;
    private final ClassLoader frameLookup;
    private final int classFileVersion;

    public LambdaWrapperEmitter(String bridgePackage, ClassLoader frameLookup) {
        this(bridgePackage, frameLookup, Opcodes.V21);
    }

    public LambdaWrapperEmitter(String bridgePackage, ClassLoader frameLookup, int classFileVersion) {
        this.bridgePackageInternal = BridgePolicy.toInternal(bridgePackage);
        this.implPackageInternal = this.bridgePackageInternal + "_impl";
        this.frameLookup = frameLookup;
        this.classFileVersion = classFileVersion;
    }

    /** Output of {@link #emit(ClassNode, LambdaSite, MethodNode)}. */
    public static final class Artifacts {
        public final String bridgeIfaceInternal;
        public final String bridgeImplInternal;
        public final byte[] bridgeIfaceBytes;
        public final byte[] bridgeImplBytes;
        /** Field name to add on the mixin (typed as the bridge interface). */
        public final String logicFieldName;
        /** Descriptor of the bridge {@code make} method: {@code (captures...)Lsam;} */
        public final String makeDescriptor;
        public final String makeName;

        Artifacts(String bridgeIfaceInternal, String bridgeImplInternal,
                  byte[] bridgeIfaceBytes, byte[] bridgeImplBytes,
                  String logicFieldName, String makeName, String makeDescriptor) {
            this.bridgeIfaceInternal = bridgeIfaceInternal;
            this.bridgeImplInternal = bridgeImplInternal;
            this.bridgeIfaceBytes = bridgeIfaceBytes;
            this.bridgeImplBytes = bridgeImplBytes;
            this.logicFieldName = logicFieldName;
            this.makeName = makeName;
            this.makeDescriptor = makeDescriptor;
        }
    }

    public Artifacts emit(ClassNode containerCn, LambdaSite site, MethodNode synthetic) {
        if (site.implMethod().getTag() != Opcodes.H_INVOKESTATIC) {
            // Defence in depth: BridgeScanner refuses to record such a site in the first place
            // (see its handleIndy). Reaching here would mean emitting a wrapper whose embedded
            // body reads a receiver slot that does not exist — a VerifyError at first use.
            throw new IllegalArgumentException(
                    "lambda site has a non-static implementation method and cannot be wrapped: "
                            + site);
        }
        // Package-qualified: two mixins with the same simple name in different packages must
        // not collide on one wrapper class or one LAMBDA_* field. See BridgeRewriter#bridgeSimpleName.
        String simple = BridgeRewriter.bridgeSimpleName(containerCn.name);
        String suffix = "$Lambda" + site.siteIndex();
        String bridgeIfaceInternal = bridgePackageInternal + "/" + simple + suffix + "Bridge";
        String bridgeImplInternal = implPackageInternal + "/" + simple + suffix + "BridgeImpl";
        Type[] captures = site.capturedTypes();
        Type samType = Type.getObjectType(site.samInternal());
        String makeName = "make";
        String makeDescriptor = Type.getMethodDescriptor(samType, captures);

        byte[] iface = emitInterface(bridgeIfaceInternal, makeName, makeDescriptor);
        byte[] impl = emitImpl(bridgeIfaceInternal, bridgeImplInternal, makeName, makeDescriptor,
                site, synthetic, captures);

        String logicField = logicFieldName(simple, site.siteIndex());
        return new Artifacts(bridgeIfaceInternal, bridgeImplInternal, iface, impl,
                logicField, makeName, makeDescriptor);
    }

    private byte[] emitInterface(String ifaceInternal, String makeName, String makeDesc) {
        ClassWriter cw = new ClasspathAwareClassWriter(
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES, frameLookup);
        cw.visit(classFileVersion,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                ifaceInternal, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                makeName, makeDesc, null, null);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Emits the impl class. The {@code make} method body constructs a SAM instance via
     * {@code INVOKEDYNAMIC LambdaMetafactory}, targeting a private-static synthetic on the
     * impl class itself. The synthetic is a literal copy of the original synthetic from the
     * mixin (same name, same descriptor, same body). Because the impl is loaded by
     * {@code ModClassLoader} at runtime, the synthetic's mod-private/Scala references resolve.
     */
    private byte[] emitImpl(String ifaceInternal, String implInternal,
                            String makeName, String makeDesc,
                            LambdaSite site, MethodNode synthetic, Type[] captures) {
        ClassWriter cw = new ClasspathAwareClassWriter(
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES, frameLookup);
        cw.visit(classFileVersion,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                implInternal, null, "java/lang/Object",
                new String[] { ifaceInternal });

        // Public no-arg ctor — same shape as existing BridgeImplEmitter.
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // make(captures...) -> SAM. Body: load captures from method params, INVOKEDYNAMIC
        // LambdaMetafactory.metafactory pointing at the embedded synthetic on this class.
        MethodVisitor make = cw.visitMethod(Opcodes.ACC_PUBLIC, makeName, makeDesc, null, null);
        make.visitCode();
        int local = 1; // skip 'this'
        for (Type t : captures) {
            make.visitVarInsn(t.getOpcode(Opcodes.ILOAD), local);
            local += t.getSize();
        }
        // Replay the ORIGINAL bootstrap verbatim, with only the implementation method
        // re-pointed at the copy embedded below. Nothing here is reconstructed:
        //   - the bootstrap handle keeps its exact name+descriptor pairing (metafactory is the
        //     fixed 6-arg form, altMetafactory the varargs one; a MethodHandle constant
        //     resolves by both, so a mismatched pair is an unlinkable call site);
        //   - samMethodType stays erased and instantiatedMethodType stays specific (deriving
        //     one value for both slots is an AbstractMethodError on every generic SAM);
        //   - altMetafactory's trailing args (FLAG_SERIALIZABLE, marker interfaces, bridge
        //     signatures) come along for free.
        // See LambdaSite's class doc.
        Object[] bsmArgs = site.relocatedBsmArgs(implInternal);
        // The indy takes the captures and returns the SAM — the same shape as the original.
        make.visitInvokeDynamicInsn(
                site.samMethodName(),
                makeDesc,
                site.bsm(),
                bsmArgs);
        make.visitInsn(Opcodes.ARETURN);
        make.visitMaxs(0, 0);
        make.visitEnd();

        // Embed the synthetic verbatim. Same name + descriptor → indices line up; no remap.
        // Force visibility to private-static-synthetic so other code can't accidentally call it.
        MethodNode embedded = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                synthetic.name, synthetic.desc, synthetic.signature,
                synthetic.exceptions == null ? null : synthetic.exceptions.toArray(new String[0]));
        // Copy the InsnList by visiting the source method into the new node.
        synthetic.accept(embedded);
        embedded.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        embedded.accept(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    public static String logicFieldName(String containerSimpleName, int siteIndex) {
        return "LAMBDA_" + containerSimpleName.replace('$', '_') + "_" + siteIndex;
    }
}

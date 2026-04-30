package de.lhns.mcdp.gradle.mixinbridges;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
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

    public LambdaWrapperEmitter(String bridgePackage, ClassLoader frameLookup) {
        this.bridgePackageInternal = BridgePolicy.toInternal(bridgePackage);
        this.implPackageInternal = this.bridgePackageInternal + "_impl";
        this.frameLookup = frameLookup;
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
        String simple = MixinRewriter.simpleName(containerCn.name);
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
        cw.visit(Opcodes.V21,
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
        cw.visit(Opcodes.V21,
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
        Handle metafactoryBsm = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        // The implementation method is a private-static synthetic on THIS impl class.
        Handle implMethod = new Handle(
                Opcodes.H_INVOKESTATIC, implInternal,
                site.implMethod().getName(), site.implMethod().getDesc(), false);
        // samMethodType / instantiatedMethodType: derive from the original synthetic descriptor
        // minus the leading captures. The simplest correct value is the synthetic's full
        // descriptor minus capture parameters — i.e., the SAM method type the JVM expects.
        Type samMethodType = computeSamMethodType(site, captures);
        // The indy returns the SAM type, taking captures.
        String indyDesc = makeDesc;
        // Read the SAM method name from the original site (LambdaMetafactory expects this).
        // We don't know the SAM method name from the bsm args alone; pass the synthetic's name
        // as a placeholder — LambdaMetafactory uses the indy NAME slot for the SAM method name.
        // We need it from the original indy. The LambdaSite doesn't carry it explicitly; the
        // synthetic's expected SAM name is also used in the original indy. Since the SAM method
        // is determined by the SAM interface, JVM looks at the SAM type's lone abstract method.
        // The "name" arg to the metafactory IS the SAM method name; not optional. We pass it
        // through from where the rewriter knows it (rewriter passes via Artifacts caller).
        make.visitInvokeDynamicInsn(
                site.samMethodNameOrFallback(),
                indyDesc,
                metafactoryBsm,
                samMethodType,
                implMethod,
                samMethodType);
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

    /**
     * Compute the SAM method type from the lambda site's information. The metafactory needs
     * a {@code MethodType} whose parameters are the SAM method's parameters and whose return
     * is the SAM method's return — not including captures.
     *
     * <p>We derive it by taking the original synthetic's descriptor and stripping the leading
     * capture parameters: synthetic = {@code (cap0, cap1, ..., samArg0, samArg1, ...)R},
     * SAM method type = {@code (samArg0, samArg1, ...)R}.</p>
     */
    private static Type computeSamMethodType(LambdaSite site, Type[] captures) {
        Type[] syntheticArgs = Type.getArgumentTypes(site.implMethod().getDesc());
        Type returnType = Type.getReturnType(site.implMethod().getDesc());
        int samArgCount = syntheticArgs.length - captures.length;
        if (samArgCount < 0) samArgCount = 0;
        Type[] samArgs = new Type[samArgCount];
        System.arraycopy(syntheticArgs, captures.length, samArgs, 0, samArgCount);
        return Type.getMethodType(returnType, samArgs);
    }

    public static String logicFieldName(String containerSimpleName, int siteIndex) {
        return "LAMBDA_" + containerSimpleName.replace('$', '_') + "_" + siteIndex;
    }

    public String bridgeIfaceFqn(String containerInternal, int siteIndex) {
        String simple = MixinRewriter.simpleName(containerInternal);
        return BridgePolicy.toDotted(bridgePackageInternal) + "."
                + simple + "$Lambda" + siteIndex + "Bridge";
    }

    public String bridgeImplFqn(String containerInternal, int siteIndex) {
        String simple = MixinRewriter.simpleName(containerInternal);
        return BridgePolicy.toDotted(implPackageInternal) + "."
                + simple + "$Lambda" + siteIndex + "BridgeImpl";
    }
}

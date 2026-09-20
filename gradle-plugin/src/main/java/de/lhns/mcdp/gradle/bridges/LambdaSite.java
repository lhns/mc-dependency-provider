package de.lhns.mcdp.gradle.bridges;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.Objects;

/**
 * A single {@code INVOKEDYNAMIC} site that resolves a closure via {@code LambdaMetafactory}
 * (or {@code altMetafactory}) into a SAM-typed instance, where the implementation method is a
 * static synthetic on the same class and that method's body touches mod-private/Scala types.
 *
 * <p>After Sponge's mixin merge, the synthetic lambda body lives on MC's class (defined by
 * FML's {@code ModuleClassLoader}). Symbol resolution for class names in that body uses MC's
 * defining loader — which can't see Scala — and the call fails with CNF. The codegen rewrites
 * the indy site into a factory call against a generated wrapper class that lives in the
 * bridge package (loaded by {@code ModClassLoader}), producing the SAM there instead. See
 * ADR-0021.</p>
 *
 * <p><b>The original bootstrap metadata is carried verbatim</b> ({@link #bsm()} and
 * {@link #bsmArgs()}) rather than re-derived. A {@code LambdaMetafactory} call site is picky in
 * ways that are invisible to a bytecode parser but fatal at link time:</p>
 * <ul>
 *   <li>{@code metafactory} is the fixed 6-arg form and {@code altMetafactory} the varargs
 *       form; a {@code MethodHandle} constant resolves by exact name <em>and</em> descriptor,
 *       so pairing one name with the other's descriptor yields an unlinkable call site.</li>
 *   <li>{@code bsmArgs[0]} ({@code samMethodType}) is <em>erased</em> — {@code ()Object} for
 *       {@code Supplier<String>} — while {@code bsmArgs[2]}
 *       ({@code instantiatedMethodType}) is specific — {@code ()String}. Conflating them
 *       produces {@code AbstractMethodError} for every generic SAM.</li>
 *   <li>{@code altMetafactory} carries trailing args (flags, marker interfaces, bridge
 *       signatures) that encode things like {@code FLAG_SERIALIZABLE}.</li>
 * </ul>
 * <p>Replaying the array against the relocated implementation method (see
 * {@link #relocatedBsmArgs(String)}) gets all three right by construction.</p>
 *
 * <p>Identity: {@code (containerInternal, ownerMethodId, indyOrdinal)} — every site is unique
 * even when two indys in the same method share SAM and capture types, because each is
 * rewritten independently and gets its own wrapper class.</p>
 */
public final class LambdaSite {

    /** Index of {@code implMethod} within a {@code LambdaMetafactory} bsm-arg array. */
    public static final int BSM_ARG_SAM_METHOD_TYPE = 0;
    public static final int BSM_ARG_IMPL_METHOD = 1;
    public static final int BSM_ARG_INSTANTIATED_METHOD_TYPE = 2;

    /** Internal name of the class containing the indy. */
    private final String containerInternal;
    /** {@code name + descriptor} of the method containing the indy. */
    private final String ownerMethodId;
    /**
     * Position of this indy among the {@code INVOKEDYNAMIC} instructions of
     * {@link #ownerMethodId}, counting from 0.
     *
     * <p>Deliberately <em>not</em> a raw instruction index. The scanner and the rewriter parse
     * the class separately, and an {@link org.objectweb.asm.tree.InsnList} contains pseudo-nodes
     * ({@code FrameNode}, {@code LabelNode}, {@code LineNumberNode}) whose presence depends on
     * the {@link org.objectweb.asm.ClassReader} flags each pass happens to use. Counting only
     * real {@code INVOKEDYNAMIC} instructions makes the index identical under every flag
     * combination, so a lambda that follows a branch (i.e. a method carrying
     * {@code StackMapTable} frames) can't silently fail to match.</p>
     */
    private final int indyOrdinal;
    /** Internal name of the SAM type the indy returns. */
    private final String samInternal;
    /** SAM method name as the indy declares it (e.g. {@code "compare"} for {@link
     *  java.util.Comparator}, {@code "get"} for {@link java.util.function.Supplier}). The
     *  metafactory uses this to identify which abstract method on the SAM interface to
     *  implement when the SAM has multiple abstract methods (rare but possible). */
    private final String samMethodName;
    /** The original bootstrap handle — {@code metafactory} or {@code altMetafactory}. */
    private final Handle bsm;
    /** The original bootstrap arguments, verbatim. */
    private final Object[] bsmArgs;
    /** The indy's descriptor: {@code (capturedTypes...)Lsam;}. The arg types are the captured
     *  values — exactly the values on the operand stack at the indy site, in order. */
    private final String indyDescriptor;
    /** Stable per-container site index, used in the wrapper class's name. */
    private final int siteIndex;

    public LambdaSite(String containerInternal, String ownerMethodId, int indyOrdinal,
                      String samInternal, String samMethodName,
                      Handle bsm, Object[] bsmArgs,
                      String indyDescriptor, int siteIndex) {
        this.containerInternal = Objects.requireNonNull(containerInternal);
        this.ownerMethodId = Objects.requireNonNull(ownerMethodId);
        this.indyOrdinal = indyOrdinal;
        this.samInternal = Objects.requireNonNull(samInternal);
        this.samMethodName = Objects.requireNonNull(samMethodName);
        this.bsm = Objects.requireNonNull(bsm);
        this.bsmArgs = Objects.requireNonNull(bsmArgs).clone();
        this.indyDescriptor = Objects.requireNonNull(indyDescriptor);
        this.siteIndex = siteIndex;
        if (this.bsmArgs.length <= BSM_ARG_INSTANTIATED_METHOD_TYPE
                || !(this.bsmArgs[BSM_ARG_IMPL_METHOD] instanceof Handle)) {
            throw new IllegalArgumentException(
                    "not a LambdaMetafactory bsm-arg shape: " + Arrays.toString(this.bsmArgs));
        }
    }

    public String containerInternal() { return containerInternal; }
    public String ownerMethodId() { return ownerMethodId; }
    public int indyOrdinal() { return indyOrdinal; }
    public String samInternal() { return samInternal; }
    public String samMethodName() { return samMethodName; }
    public Handle bsm() { return bsm; }
    public Object[] bsmArgs() { return bsmArgs.clone(); }
    public String indyDescriptor() { return indyDescriptor; }
    public int siteIndex() { return siteIndex; }

    /** The implementation-method handle the original site pointed at. */
    public Handle implMethod() {
        return (Handle) bsmArgs[BSM_ARG_IMPL_METHOD];
    }

    /**
     * The original bsm args with the implementation method re-pointed at {@code newOwnerInternal}
     * (where the codegen has copied the synthetic). Everything else — {@code samMethodType},
     * {@code instantiatedMethodType} and any trailing {@code altMetafactory} args — is passed
     * through untouched.
     *
     * <p>Only static implementation methods are relocatable; see
     * {@link BridgeScanner} for the rejection of instance synthetics.</p>
     */
    public Object[] relocatedBsmArgs(String newOwnerInternal) {
        Handle impl = implMethod();
        if (impl.getTag() != Opcodes.H_INVOKESTATIC) {
            throw new IllegalStateException(
                    "cannot relocate a non-static implementation method: " + impl);
        }
        Object[] copy = bsmArgs.clone();
        copy[BSM_ARG_IMPL_METHOD] = new Handle(
                Opcodes.H_INVOKESTATIC, newOwnerInternal,
                impl.getName(), impl.getDesc(), false);
        return copy;
    }

    /** Captured-value types, derived from the indy descriptor's arg list. */
    public Type[] capturedTypes() {
        return Type.getArgumentTypes(indyDescriptor);
    }

    @Override
    public String toString() {
        return "LambdaSite[" + containerInternal + "#" + ownerMethodId
                + " indy#" + indyOrdinal + " -> " + samInternal + " via " + implMethod() + "]";
    }
}

package de.lhns.mcdp.gradle.mixinbridges;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;

import java.util.Objects;

/**
 * A single {@code INVOKEDYNAMIC} site that resolves a closure via {@code LambdaMetafactory}
 * (or {@code altMetafactory}) into a SAM-typed instance, where the implementation method is a
 * synthetic on the same class and that method's body touches mod-private/Scala types.
 *
 * <p>After Sponge's mixin merge, the synthetic lambda body lives on MC's class (defined by
 * FML's {@code ModuleClassLoader}). Symbol resolution for class names in that body uses MC's
 * defining loader — which can't see Scala — and the call fails with CNF. The codegen rewrites
 * the indy site into a constructor call against a generated wrapper class that lives in the
 * bridge package (loaded by {@code ModClassLoader}), implementing the SAM directly. See
 * ADR-0021.</p>
 *
 * <p>Identity: {@code (containerInternal, ownerMethodId, instructionIndex)} — every site is
 * unique even when two indys in the same method share SAM and capture types, because each is
 * rewritten independently and gets its own wrapper class.</p>
 */
public final class LambdaSite {

    /** Internal name of the class containing the indy. */
    private final String containerInternal;
    /** {@code name + descriptor} of the method containing the indy. */
    private final String ownerMethodId;
    /** Instruction index within {@link #ownerMethodId}'s instruction list. Disambiguates
     *  multiple sites in the same method. */
    private final int instructionIndex;
    /** Internal name of the SAM type the indy returns. */
    private final String samInternal;
    /** SAM method name as the indy declares it (e.g. {@code "compare"} for {@link
     *  java.util.Comparator}, {@code "get"} for {@link java.util.function.Supplier}). The
     *  metafactory uses this to identify which abstract method on the SAM interface to
     *  implement when the SAM has multiple abstract methods (rare but possible). */
    private final String samMethodName;
    /** {@code name + descriptor} of the synthetic method on the container that implements the
     *  lambda body. May be present in several lambda sites (lambda inside lambda) — the rewriter
     *  emits one wrapper class per site regardless. */
    private final Handle implMethod;
    /** The indy's descriptor: {@code (capturedTypes...)Lsam;}. The arg types are the captured
     *  values — exactly the values on the operand stack at the indy site, in order. */
    private final String indyDescriptor;
    /** Stable per-container site index, used in the wrapper class's name. */
    private final int siteIndex;

    public LambdaSite(String containerInternal, String ownerMethodId, int instructionIndex,
                      String samInternal, String samMethodName, Handle implMethod,
                      String indyDescriptor, int siteIndex) {
        this.containerInternal = Objects.requireNonNull(containerInternal);
        this.ownerMethodId = Objects.requireNonNull(ownerMethodId);
        this.instructionIndex = instructionIndex;
        this.samInternal = Objects.requireNonNull(samInternal);
        this.samMethodName = Objects.requireNonNull(samMethodName);
        this.implMethod = Objects.requireNonNull(implMethod);
        this.indyDescriptor = Objects.requireNonNull(indyDescriptor);
        this.siteIndex = siteIndex;
    }

    public String containerInternal() { return containerInternal; }
    public String ownerMethodId() { return ownerMethodId; }
    public int instructionIndex() { return instructionIndex; }
    public String samInternal() { return samInternal; }
    public String samMethodName() { return samMethodName; }
    public Handle implMethod() { return implMethod; }
    public String indyDescriptor() { return indyDescriptor; }
    public int siteIndex() { return siteIndex; }

    /** Captured-value types, derived from the indy descriptor's arg list. */
    public Type[] capturedTypes() {
        return Type.getArgumentTypes(indyDescriptor);
    }

    @Override
    public String toString() {
        return "LambdaSite[" + containerInternal + "#" + ownerMethodId
                + "@" + instructionIndex + " -> " + samInternal + " via " + implMethod + "]";
    }
}

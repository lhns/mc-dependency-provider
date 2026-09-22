package de.lhns.mcdp.gradle.bridges;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link BridgeMember#bridgeMethodDescriptor} to exact strings, one rule per test.
 *
 * <p>The descriptor is computed in one place and consumed by three: the interface emitter
 * (declares the method), the impl emitter (implements it) and the rewriter (calls it). The
 * end-to-end tests only prove the three agree with each other, so a mutation applied here is
 * consistent across all of them and survives. These tests pin the value itself.</p>
 *
 * <p>Note on "erasure": {@code bridgeMethodDescriptor} performs none. Every type in the original
 * descriptor, mod-private ones included, passes through verbatim, and the owner appears as
 * {@code L<owner>;} wherever the bridge needs it. That is safe because resolving an
 * {@code invoke*} does not load the descriptor's classes through the referencing class's loader
 * (see the CONSTRUCTOR comment in BridgeMember, and PR #40/#41). The tests below pin that
 * pass-through, so a later change that starts erasing to {@code Object} has to update them
 * deliberately.</p>
 */
class BridgeMemberTest {

    private static final String OWNER = "com/example/internal/Target";

    private static String desc(BridgeMember.Kind kind, String name, String descriptor) {
        return new BridgeMember(kind, name, descriptor).bridgeMethodDescriptor(OWNER);
    }

    // --- one test per Kind ---

    @Test
    void staticMethodKeepsTheOriginalDescriptor() {
        assertEquals("(ILjava/lang/String;)J",
                desc(BridgeMember.Kind.STATIC_METHOD, "compute", "(ILjava/lang/String;)J"));
    }

    @Test
    void virtualMethodTakesTheReceiverAsTheOwnerTypeInFront() {
        assertEquals("(Lcom/example/internal/Target;ID)Ljava/lang/String;",
                desc(BridgeMember.Kind.VIRTUAL_METHOD, "describe", "(ID)Ljava/lang/String;"));
    }

    @Test
    void interfaceMethodTakesTheReceiverAsTheOwnerTypeInFront() {
        assertEquals("(Lcom/example/internal/Target;)V",
                desc(BridgeMember.Kind.INTERFACE_METHOD, "run", "()V"));
    }

    @Test
    void staticFieldGetTakesNothingAndReturnsTheFieldType() {
        assertEquals("()I", desc(BridgeMember.Kind.STATIC_FIELD_GET, "COUNT", "I"));
    }

    @Test
    void instanceFieldGetTakesTheReceiverAndReturnsTheFieldType() {
        assertEquals("(Lcom/example/internal/Target;)Ljava/util/List;",
                desc(BridgeMember.Kind.INSTANCE_FIELD_GET, "items", "Ljava/util/List;"));
    }

    @Test
    void staticFieldSetTakesTheFieldTypeAndReturnsVoid() {
        assertEquals("(Ljava/lang/String;)V",
                desc(BridgeMember.Kind.STATIC_FIELD_SET, "NAME", "Ljava/lang/String;"));
    }

    @Test
    void instanceFieldSetTakesTheReceiverThenTheFieldTypeAndReturnsVoid() {
        assertEquals("(Lcom/example/internal/Target;D)V",
                desc(BridgeMember.Kind.INSTANCE_FIELD_SET, "weight", "D"));
    }

    @Test
    void constructorKeepsTheInitArgsAndReturnsTheDeclaredTargetType() {
        // PR #41: the return was Object, which made `T t = new T(); t.f();` a VerifyError.
        assertEquals("(ILjava/lang/String;)Lcom/example/internal/Target;",
                desc(BridgeMember.Kind.CONSTRUCTOR, "<init>", "(ILjava/lang/String;)V"));
    }

    @Test
    void noArgConstructorReturnsTheDeclaredTargetType() {
        assertEquals("()Lcom/example/internal/Target;",
                desc(BridgeMember.Kind.CONSTRUCTOR, "<init>", "()V"));
    }

    @Test
    void constructorBridgeIsNamedUnderscoreNew() {
        // PR #41: "newInstance" could collide with a target's own static factory of that name
        // once the return type stopped being Object.
        assertEquals("_new",
                new BridgeMember(BridgeMember.Kind.CONSTRUCTOR, "<init>", "()V").bridgeMethodName());
    }

    @Test
    void classLiteralReturnsClass() {
        assertEquals("()Ljava/lang/Class;",
                desc(BridgeMember.Kind.CLASS_LITERAL, OWNER, "L" + OWNER + ";"));
    }

    // --- type pass-through rules ---

    @Test
    void receiverTypeComesFromTheOwnerArgumentNotTheDescriptor() {
        String nestedOwner = "com/example/internal/Outer$Inner";
        assertEquals("(Lcom/example/internal/Outer$Inner;)I",
                new BridgeMember(BridgeMember.Kind.VIRTUAL_METHOD, "size", "()I")
                        .bridgeMethodDescriptor(nestedOwner));
    }

    @Test
    void modPrivateTypesInTheDescriptorAreKeptNotErasedToObject() {
        assertEquals("(Lcom/example/internal/Target;Lcom/example/internal/Cfg;)"
                        + "Lcom/example/internal/Other;",
                desc(BridgeMember.Kind.VIRTUAL_METHOD, "convert",
                        "(Lcom/example/internal/Cfg;)Lcom/example/internal/Other;"));
    }

    @Test
    void publicAndSharedTypesInTheDescriptorAreKept() {
        assertEquals("(Ljava/util/Map;Lcom/example/api/Service;)Ljava/util/Optional;",
                desc(BridgeMember.Kind.STATIC_METHOD, "lookup",
                        "(Ljava/util/Map;Lcom/example/api/Service;)Ljava/util/Optional;"));
    }

    @Test
    void arrayTypesAreKeptWithTheirDimensionsIncludingArraysOfModPrivateTypes() {
        assertEquals("(Lcom/example/internal/Target;[[I[Lcom/example/internal/Cfg;)"
                        + "[Ljava/lang/String;",
                desc(BridgeMember.Kind.VIRTUAL_METHOD, "matrix",
                        "([[I[Lcom/example/internal/Cfg;)[Ljava/lang/String;"));
    }

    @Test
    void everyPrimitiveIncludingTwoSlotOnesIsKept() {
        assertEquals("(ZBCSIJFD)Lcom/example/internal/Target;",
                desc(BridgeMember.Kind.CONSTRUCTOR, "<init>", "(ZBCSIJFD)V"));
    }
}

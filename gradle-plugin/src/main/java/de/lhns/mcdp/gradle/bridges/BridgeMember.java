package de.lhns.mcdp.gradle.bridges;

import org.objectweb.asm.Type;

import java.util.Objects;

/**
 * One member on a target type that needs to be reached through a bridge. Each {@link Kind}
 * corresponds to a specific call-site shape in the unrewritten mixin bytecode:
 * <ul>
 *   <li>{@code STATIC_METHOD}, {@code VIRTUAL_METHOD}, {@code INTERFACE_METHOD}
 *       — INVOKE{STATIC,VIRTUAL,INTERFACE} on a mod-private owner</li>
 *   <li>{@code STATIC_FIELD_GET}, {@code INSTANCE_FIELD_GET} — GETSTATIC / GETFIELD</li>
 *   <li>{@code STATIC_FIELD_SET}, {@code INSTANCE_FIELD_SET} — PUTSTATIC / PUTFIELD</li>
 *   <li>{@code CONSTRUCTOR} — the {@code NEW + DUP + INVOKESPECIAL <init>} triplet</li>
 *   <li>{@code CLASS_LITERAL} — LDC of a {@code Class<?>} constant on a mod-private type</li>
 * </ul>
 *
 * <p>Identity is (kind, name, descriptor) so two mixins referencing the same
 * {@code FluidPhysicsMod.config()} produce a single bridge method.</p>
 */
public final class BridgeMember {

    public enum Kind {
        STATIC_METHOD,
        VIRTUAL_METHOD,
        INTERFACE_METHOD,
        STATIC_FIELD_GET,
        INSTANCE_FIELD_GET,
        STATIC_FIELD_SET,
        INSTANCE_FIELD_SET,
        CONSTRUCTOR,
        CLASS_LITERAL
    }

    private final Kind kind;
    private final String name;
    private final String descriptor;

    public BridgeMember(Kind kind, String name, String descriptor) {
        this.kind = Objects.requireNonNull(kind);
        this.name = Objects.requireNonNull(name);
        this.descriptor = Objects.requireNonNull(descriptor);
    }

    public Kind kind() { return kind; }
    public String name() { return name; }
    public String descriptor() { return descriptor; }

    /**
     * Bridge-method name on the generated interface. Static methods and methods on instances
     * coexist — disambiguate by prefix when the original {@code name} could collide. For the
     * common case the original name is used directly.
     */
    public String bridgeMethodName() {
        return switch (kind) {
            case STATIC_METHOD, VIRTUAL_METHOD, INTERFACE_METHOD -> name;
            case STATIC_FIELD_GET, INSTANCE_FIELD_GET -> "get_" + name;
            case STATIC_FIELD_SET, INSTANCE_FIELD_SET -> "set_" + name;
            // "_new", not "newInstance", and for the same reason CLASS_LITERAL uses "_class":
            // a method bridge keeps the target's own method name, so a plain identifier can
            // collide. Now that a constructor bridge returns L<target>; rather than Object, a
            // target with a static `newInstance()` factory returning itself would emit two
            // members with an identical name and descriptor -- ClassFormatError: Duplicate
            // method name. The leading underscore is not a Java method-name convention, so
            // real code is very unlikely to claim it. The emitters do no name deduplication.
            case CONSTRUCTOR -> "_new";
            case CLASS_LITERAL -> "_class";
        };
    }

    /**
     * Descriptor of the bridge method on the generated interface, derived from the original
     * call-site descriptor:
     * <ul>
     *   <li>{@code STATIC_METHOD} — original descriptor unchanged.</li>
     *   <li>{@code VIRTUAL_METHOD} / {@code INTERFACE_METHOD} — leading {@code L<owner>;} arg
     *       added to receive the instance.</li>
     *   <li>{@code STATIC_FIELD_GET} — {@code ()T}; {@code INSTANCE_FIELD_GET} —
     *       {@code (L<owner>;)T}.</li>
     *   <li>{@code STATIC_FIELD_SET} — {@code (T)V}; {@code INSTANCE_FIELD_SET} —
     *       {@code (L<owner>;T)V}.</li>
     *   <li>{@code CONSTRUCTOR} — original {@code <init>} args, return type rewritten from
     *       {@code V} to {@code L<owner>;}, the constructed type (see the comment below).</li>
     *   <li>{@code CLASS_LITERAL} — {@code ()Ljava/lang/Class;}.</li>
     * </ul>
     */
    public String bridgeMethodDescriptor(String ownerInternal) {
        return switch (kind) {
            case STATIC_METHOD -> descriptor;
            case VIRTUAL_METHOD, INTERFACE_METHOD -> {
                Type[] args = Type.getArgumentTypes(descriptor);
                Type ret = Type.getReturnType(descriptor);
                Type[] adjusted = new Type[args.length + 1];
                adjusted[0] = Type.getObjectType(ownerInternal);
                System.arraycopy(args, 0, adjusted, 1, args.length);
                yield Type.getMethodDescriptor(ret, adjusted);
            }
            case STATIC_FIELD_GET -> "()" + descriptor;
            case INSTANCE_FIELD_GET -> "(L" + ownerInternal + ";)" + descriptor;
            case STATIC_FIELD_SET -> "(" + descriptor + ")V";
            case INSTANCE_FIELD_SET -> "(L" + ownerInternal + ";" + descriptor + ")V";
            // CONSTRUCTOR: original desc has return-type V; the bridge returns the constructed
            // instance as the target type, not as Object.
            //
            // This used to erase to Object, justified by "the surrounding mixin code never holds
            // it as the mod-private type — that would be an unbridgeable locals-typing case".
            // That case is detected only for CHECKCAST/INSTANCEOF/ANEWARRAY (BridgeScanner), and
            // javac's `T t = new T(); t.f();` emits NEW/DUP/INVOKESPECIAL/ASTORE/ALOAD with no
            // CHECKCAST at all. The slot then verified as Object while the following bridge call
            // expected L<target>;, which is a VerifyError at mixin apply.
            //
            // Naming the target type here is consistent with every other Kind — an instance
            // bridge already takes its receiver as L<target>; — and is safe for the same reason:
            // resolving an invoke* does not load the descriptor's classes with the referencing
            // class's loader. The impl already ARETURNs a real instance.
            case CONSTRUCTOR -> {
                Type[] cargs = Type.getArgumentTypes(descriptor);
                yield Type.getMethodDescriptor(Type.getObjectType(ownerInternal), cargs);
            }
            case CLASS_LITERAL -> "()Ljava/lang/Class;";
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BridgeMember other)) return false;
        return kind == other.kind && name.equals(other.name) && descriptor.equals(other.descriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, name, descriptor);
    }

    @Override
    public String toString() {
        return kind + " " + name + descriptor;
    }
}

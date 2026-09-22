package de.lhns.mcdp.gradle.validate;

import de.lhns.mcdp.gradle.bridges.BridgePolicy;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks one compiled class with ASM and yields the set of every internal-name FQN it
 * references — constant pool entries, descriptors, generic signatures, method/field
 * opcode operands, {@code LDC} {@code Class<?>}/{@code MethodType}/{@code MethodHandle}/
 * {@code ConstantDynamic} constants, {@code INVOKEDYNAMIC} bootstrap methods and args,
 * {@code CHECKCAST}/{@code INSTANCEOF}/{@code NEW}/{@code ANEWARRAY} type operands, exception
 * table catch types, annotation values (including nested and {@code Class} members), type and
 * parameter annotations, and the nest/inner/permitted-subclass/
 * record-component tables.
 * <p>
 * Internal names are returned in JVM-internal form ({@code java/lang/String}). Caller
 * decides which references are "offending" — this class is a pure walker.
 * <p>
 * Completeness matters more than precision here: both ADR-0024 validators consume this set, and
 * anything missed is a <em>false negative</em> — an over-share that builds clean and then throws
 * {@code NoClassDefFoundError} (or fails verification) at runtime, which is exactly the class of
 * bug the validators exist to catch. Over-reporting, by contrast, at worst names a type the user
 * already has to think about.
 */
public final class ClassRefCollector {

    private ClassRefCollector() {}

    public static Set<String> collect(byte[] classBytes) {
        return collect(classBytes, null);
    }

    /**
     * @param bridgePackage dotted package the bridge codegen emits its interfaces into
     *                      (trailing dot optional), or {@code null}/empty for no bridge
     *                      awareness. See {@link #collect(ClassNode, String)}.
     */
    public static Set<String> collect(byte[] classBytes, String bridgePackage) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        return collect(cn, bridgePackage);
    }

    public static Set<String> collect(ClassNode cn) {
        return collect(cn, null);
    }

    /**
     * Bridge-aware walk. When {@code bridgePackage} is non-empty, the parameter/return types in
     * the descriptor of an {@code INVOKE*} instruction <em>whose owner lives in that package</em>
     * are not reported. Those descriptors are the one place where a mod-private
     * type legitimately appears inside a shared class: the bridge codegen rewrites
     * {@code config.isEnabledFor(..)} into
     * {@code INVOKEINTERFACE ConfigBridge.isEnabledFor(LConfig;..)Z}, and {@code Config} is
     * exactly the type the bridge exists to reach across the loader boundary. HotSpot does not
     * load a descriptor's classes when it resolves an invoke instruction, so these never throw
     * — {@code McdpProviderPlugin} already refuses to scan the generated bridge interfaces for
     * the same reason; this closes the gap for the rewritten caller, which <em>is</em> scanned.
     * <p>
     * The exemption is deliberately confined to instruction operands:
     * <ul>
     *   <li>The <em>owner</em> is still recorded — the bridge interface itself must be
     *       loadable, and it is, because the codegen auto-shares its package.</li>
     *   <li>Any other appearance of the mod-private type (a {@code NEW}, a {@code CHECKCAST},
     *       a field of that type, a method signature on the shared class, a catch type, an
     *       annotation value) is still reported: those are genuine over-shares that the bridge
     *       does not make safe.</li>
     *   <li>{@code LDC MethodHandle}/{@code MethodType} constants are <em>not</em> exempted
     *       even with a bridge owner. Unlike an invoke instruction, resolving a method handle
     *       or method type eagerly loads every class in its descriptor with the referencing
     *       class's loader, so such a reference really would fail on the platform loader. The
     *       rewriter never emits one (lambda sites become {@code INVOKEINTERFACE make(..)}), so
     *       nothing legitimate is lost.</li>
     *   <li>The sibling {@code <bridgePackage>_impl} package is <em>not</em> covered: the
     *       trailing {@code /} in the prefix excludes it by construction. Impls are
     *       intentionally not shared (ADR-0021 errata) and are child-loaded by the per-mod
     *       loader, so a shared class naming one is a real {@code NoClassDefFoundError}.</li>
     * </ul>
     *
     * @param bridgePackage dotted bridge-interface package (trailing dot optional), or
     *                      {@code null}/empty to disable the exemption entirely.
     */
    public static Set<String> collect(ClassNode cn, String bridgePackage) {
        String bridgeOwnerPrefix = bridgeOwnerPrefix(bridgePackage);
        Set<String> refs = new LinkedHashSet<>();

        // Class header.
        if (cn.superName != null) refs.add(cn.superName);
        if (cn.interfaces != null) refs.addAll(cn.interfaces);
        if (cn.signature != null) collectFromSignature(cn.signature, refs);
        collectFromAnnotations(cn.visibleAnnotations, refs);
        collectFromAnnotations(cn.invisibleAnnotations, refs);
        collectFromAnnotations(cn.visibleTypeAnnotations, refs);
        collectFromAnnotations(cn.invisibleTypeAnnotations, refs);

        // Nest/inner/sealed/record tables. A class listed here is resolved by the JVM during
        // access checks and reflection, so it has to be loadable from the same loader.
        if (cn.nestHostClass != null) refs.add(cn.nestHostClass);
        if (cn.nestMembers != null) refs.addAll(cn.nestMembers);
        if (cn.permittedSubclasses != null) refs.addAll(cn.permittedSubclasses);
        if (cn.outerClass != null) refs.add(cn.outerClass);
        if (cn.outerMethodDesc != null) addMethodType(cn.outerMethodDesc, refs);
        if (cn.innerClasses != null) {
            for (InnerClassNode ic : cn.innerClasses) {
                if (ic.name != null) refs.add(ic.name);
                if (ic.outerName != null) refs.add(ic.outerName);
            }
        }
        if (cn.recordComponents != null) {
            for (RecordComponentNode rc : cn.recordComponents) {
                addType(Type.getType(rc.descriptor), refs);
                if (rc.signature != null) collectFromSignature(rc.signature, refs);
                collectFromAnnotations(rc.visibleAnnotations, refs);
                collectFromAnnotations(rc.invisibleAnnotations, refs);
                collectFromAnnotations(rc.visibleTypeAnnotations, refs);
                collectFromAnnotations(rc.invisibleTypeAnnotations, refs);
            }
        }

        if (cn.fields != null) {
            for (FieldNode f : cn.fields) {
                addType(Type.getType(f.desc), refs);
                if (f.signature != null) collectFromSignature(f.signature, refs);
                collectFromAnnotations(f.visibleAnnotations, refs);
                collectFromAnnotations(f.invisibleAnnotations, refs);
                collectFromAnnotations(f.visibleTypeAnnotations, refs);
                collectFromAnnotations(f.invisibleTypeAnnotations, refs);
                addConstant(f.value, refs);
            }
        }

        if (cn.methods != null) {
            for (MethodNode m : cn.methods) {
                addMethodType(m.desc, refs);
                if (m.signature != null) collectFromSignature(m.signature, refs);
                if (m.exceptions != null) refs.addAll(m.exceptions);
                collectFromAnnotations(m.visibleAnnotations, refs);
                collectFromAnnotations(m.invisibleAnnotations, refs);
                collectFromAnnotations(m.visibleTypeAnnotations, refs);
                collectFromAnnotations(m.invisibleTypeAnnotations, refs);
                collectFromAnnotationArrays(m.visibleParameterAnnotations, refs);
                collectFromAnnotationArrays(m.invisibleParameterAnnotations, refs);
                collectFromAnnotationValue(m.annotationDefault, refs);
                // Catch types. A `catch (MyModException e)` leaves no other trace in the class
                // — no descriptor, no opcode operand — yet the verifier resolves the exception
                // table's type when the method is first linked.
                if (m.tryCatchBlocks != null) {
                    for (TryCatchBlockNode tcb : m.tryCatchBlocks) {
                        if (tcb.type != null) refs.add(tcb.type); // null = `finally` handler
                        collectFromAnnotations(tcb.visibleTypeAnnotations, refs);
                        collectFromAnnotations(tcb.invisibleTypeAnnotations, refs);
                    }
                }
                // LocalVariableTable is deliberately NOT collected. The JVM never resolves an
                // LVT descriptor -- it is a debug attribute (JVMS 4.7.13) read only by debuggers
                // and stack-walking tools -- so unlike a catch type or an LDC MethodType it
                // cannot cause a load, and collecting it can only ever produce false positives.
                // It cannot hide a real one either: any local typed T took its value from an
                // instruction, and that instruction's owner, descriptor or CHECKCAST is already
                // collected -- except when the producer is a bridge call, which is exactly the
                // reference the bridge exists to make safe. That exception is why this matters:
                // BridgeRewriter reads with SKIP_FRAMES and never touches localVariables, so a
                // javac-compiled `T t = bridgedCall();` in a shared package kept an LVT entry
                // naming T and failed a mod that runs clean.
                collectFromAnnotations(m.visibleLocalVariableAnnotations, refs);
                collectFromAnnotations(m.invisibleLocalVariableAnnotations, refs);
                if (m.instructions != null) {
                    for (AbstractInsnNode insn : m.instructions) {
                        collectFromAnnotations(insn.visibleTypeAnnotations, refs);
                        collectFromAnnotations(insn.invisibleTypeAnnotations, refs);
                        // if/else-if on `instanceof` rather than a pattern switch: this
                        // module targets Java 17 so the published plugin loads on a Gradle 7
                        // daemon (ForgeGradle 5.1, the only FG line for MC <= 1.18, pins
                        // Gradle 7). Switch patterns are Java 21 and would silently re-break
                        // the Forge 1.17/1.18 CI cells. Do not "modernise" this back.
                        if (insn instanceof TypeInsnNode t) {
                            // CHECKCAST/INSTANCEOF/ANEWARRAY on an array type carry a
                            // descriptor (`[Ljava/lang/String;`), not an internal name, so
                            // this must go through getObjectType/addType to reach the element
                            // type -- adding the raw operand reports `[Ljava.lang.String;` as
                            // an unresolvable reference and fails the build on legal code.
                            addType(Type.getObjectType(t.desc), refs);
                        } else if (insn instanceof FieldInsnNode f) {
                            refs.add(f.owner);
                            // No bridge exemption here on purpose: neither BridgeInterfaceEmitter
                            // nor LambdaWrapperEmitter ever emits a field on a bridge type
                            // (they emit interfaces; the rewriter turns every field access on a
                            // bridged target into an INVOKEINTERFACE getter/setter). A GETFIELD
                            // with a bridge owner therefore cannot come from the codegen, and
                            // exempting it would only widen the hole for hand-written code.
                            addType(Type.getType(f.desc), refs);
                        } else if (insn instanceof MethodInsnNode mi) {
                            // `owner` is an array descriptor for array-member calls
                            // such as `arr.clone()`; same normalisation as above.
                            addType(Type.getObjectType(mi.owner), refs);
                            // A call routed through a generated bridge carries the mod-private
                            // types it exists to reach in its descriptor. Record the bridge, not
                            // what the bridge reaches. See collect(ClassNode, String).
                            if (!isBridgeOwner(mi.owner, bridgeOwnerPrefix)) {
                                addMethodType(mi.desc, refs);
                            }
                        } else if (insn instanceof LdcInsnNode l) {
                            addConstant(l.cst, refs);
                        } else if (insn instanceof InvokeDynamicInsnNode idy) {
                            addMethodType(idy.desc, refs);
                            addHandle(idy.bsm, refs);
                            if (idy.bsmArgs != null) {
                                for (Object arg : idy.bsmArgs) addConstant(arg, refs);
                            }
                        } else if (insn instanceof MultiANewArrayInsnNode ma) {
                            addType(Type.getType(ma.desc), refs);
                        }
                    }
                }
            }
        }

        return refs;
    }

    /**
     * Normalize a dotted bridge package into an internal-name prefix with a trailing {@code /},
     * or {@code null} when bridge awareness is off. The trailing separator is what keeps the
     * sibling {@code <bridgePackage>_impl} package (and any {@code mcdp_bridgesOther} package)
     * outside the exemption.
     */
    static String bridgeOwnerPrefix(String bridgePackage) {
        if (bridgePackage == null) return null;
        String p = bridgePackage.trim();
        while (p.endsWith(".")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) return null;
        return BridgePolicy.toInternal(p) + "/";
    }

    private static boolean isBridgeOwner(String ownerInternal, String bridgeOwnerPrefix) {
        return bridgeOwnerPrefix != null && ownerInternal != null
                && ownerInternal.startsWith(bridgeOwnerPrefix);
    }

    private static void addMethodType(String methodDesc, Set<String> refs) {
        Type mt = Type.getMethodType(methodDesc);
        addType(mt.getReturnType(), refs);
        for (Type a : mt.getArgumentTypes()) addType(a, refs);
    }

    private static void addType(Type t, Set<String> refs) {
        switch (t.getSort()) {
            case Type.OBJECT -> refs.add(t.getInternalName());
            case Type.ARRAY -> addType(t.getElementType(), refs);
            // An `LDC MethodType` constant resolves every argument and return type in the
            // descriptor against the calling class's loader, same as a method descriptor does.
            case Type.METHOD -> {
                addType(t.getReturnType(), refs);
                for (Type a : t.getArgumentTypes()) addType(a, refs);
            }
            default -> {}
        }
    }

    /**
     * Constant-pool constant of any shape reachable from {@code LDC}, a field's initial value,
     * or a bootstrap-method argument. {@code String}/boxed primitives carry no type reference.
     */
    private static void addConstant(Object cst, Set<String> refs) {
        // instanceof chain, not a pattern switch -- see the note in collectFromMethods.
        if (cst instanceof Type t) {
            addType(t, refs);
        } else if (cst instanceof Handle h) {
            // Method references and other bsm args: the handle names an owner and a descriptor,
            // both of which the JVM resolves when the call site links.
            addHandle(h, refs);
        } else if (cst instanceof ConstantDynamic cd) {
            addType(Type.getType(cd.getDescriptor()), refs);
            addHandle(cd.getBootstrapMethod(), refs);
            for (int i = 0; i < cd.getBootstrapMethodArgumentCount(); i++) {
                addConstant(cd.getBootstrapMethodArgument(i), refs);
            }
        }
    }

    private static void addHandle(Handle h, Set<String> refs) {
        if (h == null) return;
        // Same array-descriptor case as TypeInsnNode: a MethodHandle on `String[]::clone`
        // has an array owner.
        addType(Type.getObjectType(h.getOwner()), refs);
        String desc = h.getDesc();
        if (desc == null) return;
        // Field handles (GETFIELD/PUTSTATIC/...) carry a field descriptor, method handles a
        // method descriptor; the leading '(' is the only discriminator we need.
        if (desc.startsWith("(")) addMethodType(desc, refs);
        else addType(Type.getType(desc), refs);
    }

    private static void collectFromAnnotationArrays(List<AnnotationNode>[] perParameter, Set<String> refs) {
        if (perParameter == null) return;
        for (List<AnnotationNode> anns : perParameter) collectFromAnnotations(anns, refs);
    }

    private static void collectFromAnnotations(List<? extends AnnotationNode> anns, Set<String> refs) {
        if (anns == null) return;
        for (AnnotationNode a : anns) collectFromAnnotation(a, refs);
    }

    private static void collectFromAnnotation(AnnotationNode a, Set<String> refs) {
        if (a == null) return;
        if (a.desc != null) addType(Type.getType(a.desc), refs);
        collectFromAnnotationValues(a.values, refs);
    }

    /**
     * {@code AnnotationNode.values} is a flat name/value list. Values carry type references in
     * three shapes, all of which the annotation's reader resolves against the annotated class's
     * loader: a {@link Type} for a {@code Class} member ({@code @Something(MyModClass.class)}),
     * a two-element {@code String[]} of {@code {enumDesc, constantName}} for an enum member, and
     * a nested {@link AnnotationNode}. {@code List} values wrap arrays of any of the above.
     */
    private static void collectFromAnnotationValues(List<Object> values, Set<String> refs) {
        if (values == null) return;
        for (Object v : values) collectFromAnnotationValue(v, refs);
    }

    private static void collectFromAnnotationValue(Object v, Set<String> refs) {
        // instanceof chain, not a pattern switch -- see the note in collectFromMethods.
        if (v instanceof Type t) {
            addType(t, refs);
        } else if (v instanceof AnnotationNode nested) {
            collectFromAnnotation(nested, refs);
        } else if (v instanceof String[] enumConst) {
            // {descriptor, constant-name}; only the descriptor is a type reference.
            if (enumConst.length > 0 && enumConst[0] != null) {
                addType(Type.getType(enumConst[0]), refs);
            }
        } else if (v instanceof List<?> list) {
            for (Object e : list) collectFromAnnotationValue(e, refs);
        }
    }

    private static void collectFromSignature(String signature, Set<String> refs) {
        new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
            @Override
            public void visitClassType(String name) { refs.add(name); }
        });
    }
}

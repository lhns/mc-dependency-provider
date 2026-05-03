package de.lhns.mcdp.gradle.mixinbridges;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites a mixin class's method bodies to dispatch through bridge interfaces. Adds one
 * synthetic static field per target type ({@code LOGIC_<TargetSimpleName>}); replaces each
 * cross-classloader call site with a stack-juggling sequence that invokes the bridge.
 *
 * <p>Class headers are not touched: {@code interfaces}, {@code superName}, declared field
 * descriptors, and all annotations are preserved byte-for-byte. {@code LineNumberTable} is
 * preserved because we walk the existing {@link InsnList} and only replace specific call
 * sites.</p>
 */
public final class MixinRewriter {

    private final BridgePolicy policy;
    private final String bridgePackageInternal;
    private final ClassLoader frameLookup;

    public MixinRewriter(BridgePolicy policy, String bridgePackage) {
        this(policy, bridgePackage, ClassLoader.getSystemClassLoader());
    }

    public MixinRewriter(BridgePolicy policy, String bridgePackage, ClassLoader frameLookup) {
        this.policy = policy;
        this.bridgePackageInternal = BridgePolicy.toInternal(bridgePackage);
        this.frameLookup = frameLookup;
    }

    /**
     * Apply the rewrite plan to the given class. {@code targets} maps target internal name to
     * the set of {@link BridgeMember}s that need a bridge entry — typically the output of
     * {@link BridgeMixinScanner}.
     *
     * @return rewritten class bytes; the input is left untouched.
     */
    public byte[] rewrite(byte[] classBytes, Map<String, List<BridgeMember>> targets) {
        return rewrite(classBytes, targets, List.of(), Map.of());
    }

    /**
     * Variant covering ADR-0021 lambda-site rewriting. {@code lambdaSites} is the per-site
     * list from {@link BridgeMixinScanner}; {@code lambdaArtifactsBySite} maps each site's
     * {@link LambdaSite#siteIndex()} to the bridge interface internal name + LOGIC field name
     * + make-method name and descriptor — produced by {@link LambdaWrapperEmitter}.
     */
    public byte[] rewrite(byte[] classBytes, Map<String, List<BridgeMember>> targets,
                          List<LambdaSite> lambdaSites,
                          Map<Integer, LambdaWrapperEmitter.Artifacts> lambdaArtifactsBySite) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        rewrite(cn, targets, lambdaSites, lambdaArtifactsBySite);
        ClassWriter cw = new ClasspathAwareClassWriter(
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES, frameLookup);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public void rewrite(ClassNode cn, Map<String, List<BridgeMember>> targets) {
        rewrite(cn, targets, List.of(), Map.of());
    }

    public void rewrite(ClassNode cn, Map<String, List<BridgeMember>> targets,
                        List<LambdaSite> lambdaSites,
                        Map<Integer, LambdaWrapperEmitter.Artifacts> lambdaArtifactsBySite) {
        Map<String, String> targetToBridge = new LinkedHashMap<>();
        List<ClinitInit> inits = new ArrayList<>();
        for (String target : targets.keySet()) {
            String bridgeInternal = bridgePackageInternal + "/" + simpleName(target) + "Bridge";
            targetToBridge.put(target, bridgeInternal);
            String fieldName = logicFieldName(target);
            // Skip if a previous run already added the field — codegen is incremental.
            boolean exists = false;
            for (FieldNode fn : cn.fields) {
                if (fn.name.equals(fieldName)) { exists = true; break; }
            }
            if (!exists) {
                FieldNode fn = new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        fieldName, "L" + bridgeInternal + ";", null, null);
                cn.fields.add(fn);
            }
            inits.add(new ClinitInit(fieldName, bridgeInternal));
        }
        // Add a LAMBDA_<simple>_<n> field per lambda site (typed as the per-site bridge
        // interface) and queue its <clinit> initializer.
        for (LambdaSite site : lambdaSites) {
            LambdaWrapperEmitter.Artifacts art = lambdaArtifactsBySite.get(site.siteIndex());
            if (art == null) continue;
            boolean exists = false;
            for (FieldNode fn : cn.fields) {
                if (fn.name.equals(art.logicFieldName)) { exists = true; break; }
            }
            if (!exists) {
                FieldNode fn = new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        art.logicFieldName, "L" + art.bridgeIfaceInternal + ";", null, null);
                cn.fields.add(fn);
            }
            inits.add(new ClinitInit(art.logicFieldName, art.bridgeIfaceInternal));
        }
        // Index lambda sites by (methodId, instructionIndex) for the rewriter to look them up
        // during the method walk.
        Map<String, Map<Integer, LambdaSite>> sitesByMethod = new LinkedHashMap<>();
        for (LambdaSite site : lambdaSites) {
            sitesByMethod
                    .computeIfAbsent(site.ownerMethodId(), k -> new LinkedHashMap<>())
                    .put(site.instructionIndex(), site);
        }
        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            rewriteMethod(cn, m, targets, targetToBridge,
                    sitesByMethod.getOrDefault(m.name + m.desc, Map.of()),
                    lambdaArtifactsBySite);
        }
        mergeOrCreateClinit(cn, inits);
    }

    private record ClinitInit(String fieldName, String bridgeInternal) {}

    /**
     * Emit (or extend) {@code <clinit>} so each LOGIC_* field is initialized on first class
     * use by calling back to {@code McdpProvider.resolveAutoBridgeImpl(mixinFqn, fieldName)}.
     * Eager fast-path: when sponge merges this {@code <clinit>} into the target's class init,
     * the populator runs once and every subsequent {@code GETSTATIC LOGIC_*} reads a non-null
     * field directly. The lazy fallback at each call site (see {@link #emitLogicLoad}) handles
     * cases where the populator hasn't run yet — the headline example is
     * {@code @Inject(at=HEAD)} on the target's own {@code <clinit>}, where the merged populator
     * sits later in the same {@code <clinit>} than the HEAD-injected handler.
     *
     * <p>Idempotent: if {@code <clinit>} already PUTSTATICs a given field (a previous codegen
     * run, or hand-written init), that field's init block is skipped.</p>
     */
    private void mergeOrCreateClinit(ClassNode cn, List<ClinitInit> inits) {
        if (inits.isEmpty()) return;
        String mixinDotted = BridgePolicy.toDotted(cn.name);

        MethodNode clinit = null;
        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name) && "()V".equals(mn.desc)) {
                clinit = mn;
                break;
            }
        }
        Set<String> alreadyPut = new HashSet<>();
        if (clinit != null && clinit.instructions != null) {
            for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.PUTSTATIC && insn instanceof FieldInsnNode fin
                        && fin.owner.equals(cn.name)) {
                    alreadyPut.add(fin.name);
                }
            }
        }

        InsnList block = new InsnList();
        boolean anyEmitted = false;
        for (ClinitInit ci : inits) {
            if (alreadyPut.contains(ci.fieldName())) continue;
            block.add(new LdcInsnNode(mixinDotted));
            block.add(new LdcInsnNode(ci.fieldName()));
            block.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "de/lhns/mcdp/api/McdpProvider",
                    "resolveAutoBridgeImpl",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
                    false));
            block.add(new TypeInsnNode(Opcodes.CHECKCAST, ci.bridgeInternal()));
            block.add(new FieldInsnNode(
                    Opcodes.PUTSTATIC,
                    cn.name,
                    ci.fieldName(),
                    "L" + ci.bridgeInternal() + ";"));
            anyEmitted = true;
        }
        if (!anyEmitted) return;

        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions = new InsnList();
            clinit.instructions.add(block);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(clinit);
        } else {
            // Prepend so the LOGIC_* fields are populated before any existing static init
            // touches them.
            clinit.instructions.insert(block);
        }
    }

    private void rewriteMethod(ClassNode cn, MethodNode m,
                               Map<String, List<BridgeMember>> targets,
                               Map<String, String> targetToBridge,
                               Map<Integer, LambdaSite> sitesInThisMethod,
                               Map<Integer, LambdaWrapperEmitter.Artifacts> artifactsBySite) {
        // Pre-pass: pair each INVOKESPECIAL <init> on a target owner with its NEW + DUP. We do
        // this once forward-walking with a LIFO stack of unmatched NEWs — this matches the
        // nesting of constructor calls produced by javac/scalac/kotlinc. Bare NEWs without a
        // following DUP are skipped (unusual user-code pattern; the rewriter leaves them).
        Map<MethodInsnNode, AbstractInsnNode[]> ctorTriplets = findCtorTriplets(m, targets.keySet());
        Set<AbstractInsnNode> ctorMarkers = new HashSet<>();
        for (AbstractInsnNode[] pair : ctorTriplets.values()) {
            ctorMarkers.add(pair[0]); // NEW
            ctorMarkers.add(pair[1]); // DUP
        }

        AbstractInsnNode insn = m.instructions.getFirst();
        int instructionIndex = 0;
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            int op = insn.getOpcode();
            if (op == Opcodes.INVOKESPECIAL && insn instanceof MethodInsnNode min
                    && "<init>".equals(min.name) && ctorTriplets.containsKey(min)) {
                AbstractInsnNode[] pair = ctorTriplets.get(min);
                rewriteConstructor(cn, m, min, pair[0], pair[1], targetToBridge.get(min.owner));
            } else if ((op == Opcodes.INVOKESTATIC || op == Opcodes.INVOKEVIRTUAL
                    || op == Opcodes.INVOKEINTERFACE) && insn instanceof MethodInsnNode min
                    && targets.containsKey(min.owner)) {
                rewriteMethodCall(cn, m, min, targetToBridge.get(min.owner));
            } else if ((op == Opcodes.GETSTATIC || op == Opcodes.GETFIELD)
                    && insn instanceof FieldInsnNode fin && targets.containsKey(fin.owner)) {
                rewriteFieldRead(cn, m, fin, targetToBridge.get(fin.owner));
            } else if ((op == Opcodes.PUTSTATIC || op == Opcodes.PUTFIELD)
                    && insn instanceof FieldInsnNode fin && targets.containsKey(fin.owner)) {
                rewriteFieldWrite(cn, m, fin, targetToBridge.get(fin.owner));
            } else if (op == Opcodes.LDC && insn instanceof LdcInsnNode ldc
                    && ldc.cst instanceof Type t && t.getSort() == Type.OBJECT
                    && targets.containsKey(t.getInternalName())) {
                rewriteClassLiteral(cn, m, ldc, targetToBridge.get(t.getInternalName()));
            } else if (op == Opcodes.INVOKEDYNAMIC && insn instanceof InvokeDynamicInsnNode indy
                    && sitesInThisMethod.containsKey(instructionIndex)) {
                LambdaSite site = sitesInThisMethod.get(instructionIndex);
                LambdaWrapperEmitter.Artifacts art = artifactsBySite.get(site.siteIndex());
                if (art != null) {
                    rewriteLambdaIndy(cn, m, indy, site, art);
                }
            }
            // NEW/DUP nodes consumed by ctorTriplets are removed inside rewriteConstructor;
            // anything we didn't recognize is left untouched.
            insn = next;
            instructionIndex++;
        }
    }

    /**
     * Replace an {@code INVOKEDYNAMIC LambdaMetafactory} site with a stack-juggle that pushes
     * the per-site {@code LAMBDA_*} static field (typed as the bridge interface) under the
     * captures and INVOKEINTERFACEs the bridge's {@code make} factory. The factory returns the
     * SAM instance — same shape as the original indy. ADR-0021.
     */
    private void rewriteLambdaIndy(ClassNode cn, MethodNode m, InvokeDynamicInsnNode indy,
                                   LambdaSite site, LambdaWrapperEmitter.Artifacts art) {
        Type[] captures = Type.getArgumentTypes(indy.desc);
        InsnList replacement = new InsnList();
        // Spill captures to fresh locals (in reverse stack order).
        int next = nextFreeLocal(m);
        int[] capLocals = new int[captures.length];
        for (int i = 0; i < captures.length; i++) {
            capLocals[i] = next;
            next += captures[i].getSize();
        }
        m.maxLocals = Math.max(m.maxLocals, next);
        for (int i = captures.length - 1; i >= 0; i--) {
            replacement.add(new VarInsnNode(captures[i].getOpcode(Opcodes.ISTORE), capLocals[i]));
        }
        // Push LAMBDA bridge ref (LAMBDA_* field on the container) with self-init guard.
        emitLogicLoad(replacement, cn, art.logicFieldName, art.bridgeIfaceInternal);
        // Reload captures.
        for (int i = 0; i < captures.length; i++) {
            replacement.add(new VarInsnNode(captures[i].getOpcode(Opcodes.ILOAD), capLocals[i]));
        }
        // INVOKEINTERFACE bridge.make(captures...) -> SAM
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, art.bridgeIfaceInternal,
                art.makeName, art.makeDescriptor, true));
        m.instructions.insertBefore(indy, replacement);
        m.instructions.remove(indy);
    }

    private static Map<MethodInsnNode, AbstractInsnNode[]> findCtorTriplets(
            MethodNode m, Set<String> targetOwners) {
        Map<MethodInsnNode, AbstractInsnNode[]> result = new HashMap<>();
        Deque<AbstractInsnNode[]> uninits = new ArrayDeque<>();
        AbstractInsnNode insn = m.instructions.getFirst();
        while (insn != null) {
            int op = insn.getOpcode();
            if (op == Opcodes.NEW && insn instanceof TypeInsnNode tin
                    && targetOwners.contains(tin.desc)) {
                AbstractInsnNode dup = insn.getNext();
                while (dup != null && dup.getOpcode() < 0) dup = dup.getNext(); // skip pseudo
                if (dup != null && dup.getOpcode() == Opcodes.DUP) {
                    uninits.push(new AbstractInsnNode[]{insn, dup});
                }
            } else if (op == Opcodes.INVOKESPECIAL && insn instanceof MethodInsnNode min
                    && "<init>".equals(min.name) && targetOwners.contains(min.owner)) {
                if (!uninits.isEmpty()) {
                    AbstractInsnNode[] pair = uninits.pop();
                    TypeInsnNode tin = (TypeInsnNode) pair[0];
                    if (tin.desc.equals(min.owner)) {
                        result.put(min, pair);
                    }
                }
            }
            insn = insn.getNext();
        }
        return result;
    }

    private void rewriteMethodCall(ClassNode cn, MethodNode m, MethodInsnNode min,
                                   String bridgeInternal) {
        int op = min.getOpcode();
        boolean isStatic = (op == Opcodes.INVOKESTATIC);
        Type[] argTypes = Type.getArgumentTypes(min.desc);
        Type retType = Type.getReturnType(min.desc);
        BridgeMember.Kind kind = switch (op) {
            case Opcodes.INVOKESTATIC -> BridgeMember.Kind.STATIC_METHOD;
            case Opcodes.INVOKEVIRTUAL -> BridgeMember.Kind.VIRTUAL_METHOD;
            default -> BridgeMember.Kind.INTERFACE_METHOD;
        };
        BridgeMember bm = new BridgeMember(kind, min.name, min.desc);
        String bridgeDesc = bm.bridgeMethodDescriptor(min.owner);

        // Stack on entry: [..., (receiver), arg0, arg1, ..., argN]
        // Stack we want:  [..., LOGIC, (receiver), arg0, ..., argN]
        // Strategy: spill all (receiver, args) into fresh locals; push LOGIC; reload locals.

        InsnList replacement = new InsnList();
        int[] argLocals = new int[argTypes.length];
        // Allocate receiver local first (if instance call) so the stack pop order is right.
        // We pop in reverse: argN first, ..., arg0, receiver.
        int receiverLocal = -1;
        int next = nextFreeLocal(m);
        if (!isStatic) {
            receiverLocal = next;
            next += 1;
        }
        for (int i = 0; i < argTypes.length; i++) {
            argLocals[i] = next;
            next += argTypes[i].getSize();
        }
        m.maxLocals = Math.max(m.maxLocals, next);

        // Pop in reverse order: store argN..arg0 then receiver.
        for (int i = argTypes.length - 1; i >= 0; i--) {
            replacement.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
        }
        if (!isStatic) {
            replacement.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
        }
        // Push LOGIC bridge ref (with self-init guard).
        emitLogicLoad(replacement, cn, logicFieldName(min.owner), bridgeInternal);
        // Push receiver (for instance bridges, it's the first bridge-method arg).
        if (!isStatic) {
            replacement.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        }
        // Push args back in original order.
        for (int i = 0; i < argTypes.length; i++) {
            replacement.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ILOAD), argLocals[i]));
        }
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                bridgeInternal,
                bm.bridgeMethodName(),
                bridgeDesc,
                true));
        m.instructions.insertBefore(min, replacement);
        m.instructions.remove(min);
        // Return type is preserved (bridge has the same return type), so no cast/conversion
        // needed downstream.
        // Suppress unused warning for retType.
        if (retType.getSize() < 0) throw new AssertionError();
    }

    private void rewriteFieldRead(ClassNode cn, MethodNode m, FieldInsnNode fin,
                                  String bridgeInternal) {
        int op = fin.getOpcode();
        boolean isStatic = (op == Opcodes.GETSTATIC);
        BridgeMember bm = new BridgeMember(
                isStatic ? BridgeMember.Kind.STATIC_FIELD_GET : BridgeMember.Kind.INSTANCE_FIELD_GET,
                fin.name, fin.desc);
        String bridgeDesc = bm.bridgeMethodDescriptor(fin.owner);

        InsnList replacement = new InsnList();
        if (isStatic) {
            // Stack: [...] -> [..., LOGIC] -> [..., result]
            emitLogicLoad(replacement, cn, logicFieldName(fin.owner), bridgeInternal);
            replacement.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    bridgeInternal,
                    bm.bridgeMethodName(),
                    bridgeDesc,
                    true));
        } else {
            // Stack: [..., receiver] -> [..., LOGIC, receiver] -> [..., result]
            // Need to insert LOGIC under the receiver; ASM SWAP only works on category-1 values
            // and receiver is always one slot, so SWAP is safe here.
            emitLogicLoad(replacement, cn, logicFieldName(fin.owner), bridgeInternal);
            replacement.add(new org.objectweb.asm.tree.InsnNode(Opcodes.SWAP));
            replacement.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    bridgeInternal,
                    bm.bridgeMethodName(),
                    bridgeDesc,
                    true));
        }
        m.instructions.insertBefore(fin, replacement);
        m.instructions.remove(fin);
    }

    /**
     * Rewrite a {@code NEW T / DUP / args / INVOKESPECIAL T.<init>(args)V} triplet into a single
     * bridged call returning the constructed object as {@code Object}. Removes the NEW and DUP;
     * replaces the INVOKESPECIAL with a stack-juggle to push LOGIC under the args and an
     * INVOKEINTERFACE on the bridge. Code that holds the result as the mod-private type would
     * have separately tripped the locals-typing limit (CHECKCAST/ASTORE-as-T) — those cases are
     * out of scope and surfaced as warnings by the scanner.
     */
    private void rewriteConstructor(ClassNode cn, MethodNode m, MethodInsnNode min,
                                    AbstractInsnNode newNode, AbstractInsnNode dupNode,
                                    String bridgeInternal) {
        Type[] argTypes = Type.getArgumentTypes(min.desc);
        BridgeMember bm = new BridgeMember(BridgeMember.Kind.CONSTRUCTOR, "<init>", min.desc);
        String bridgeDesc = bm.bridgeMethodDescriptor(min.owner);

        // Drop the NEW and DUP — the constructed value comes from the bridge call instead.
        m.instructions.remove(newNode);
        m.instructions.remove(dupNode);

        // At INVOKESPECIAL site, stack is [..., arg0, ..., argN]. Re-use the same juggle
        // strategy as a static call: spill args, push LOGIC, reload args, INVOKEINTERFACE.
        InsnList replacement = new InsnList();
        int[] argLocals = new int[argTypes.length];
        int next = nextFreeLocal(m);
        for (int i = 0; i < argTypes.length; i++) {
            argLocals[i] = next;
            next += argTypes[i].getSize();
        }
        m.maxLocals = Math.max(m.maxLocals, next);

        for (int i = argTypes.length - 1; i >= 0; i--) {
            replacement.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
        }
        emitLogicLoad(replacement, cn, logicFieldName(min.owner), bridgeInternal);
        for (int i = 0; i < argTypes.length; i++) {
            replacement.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ILOAD), argLocals[i]));
        }
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, bridgeInternal,
                bm.bridgeMethodName(), bridgeDesc, true));
        m.instructions.insertBefore(min, replacement);
        m.instructions.remove(min);
    }

    /**
     * Rewrite a {@code PUTSTATIC owner.field T} or {@code PUTFIELD owner.field T} call into a
     * setter method on the bridge. Mirror image of {@link #rewriteFieldRead}.
     */
    private void rewriteFieldWrite(ClassNode cn, MethodNode m, FieldInsnNode fin,
                                   String bridgeInternal) {
        boolean isStatic = (fin.getOpcode() == Opcodes.PUTSTATIC);
        BridgeMember bm = new BridgeMember(
                isStatic ? BridgeMember.Kind.STATIC_FIELD_SET
                        : BridgeMember.Kind.INSTANCE_FIELD_SET,
                fin.name, fin.desc);
        String bridgeDesc = bm.bridgeMethodDescriptor(fin.owner);
        Type valueType = Type.getType(fin.desc);

        InsnList replacement = new InsnList();
        if (isStatic) {
            // Stack on entry: [..., value]
            // Spill value, push LOGIC, reload value, INVOKEINTERFACE.
            int valueLocal = nextFreeLocal(m);
            m.maxLocals = Math.max(m.maxLocals, valueLocal + valueType.getSize());
            replacement.add(new VarInsnNode(valueType.getOpcode(Opcodes.ISTORE), valueLocal));
            emitLogicLoad(replacement, cn, logicFieldName(fin.owner), bridgeInternal);
            replacement.add(new VarInsnNode(valueType.getOpcode(Opcodes.ILOAD), valueLocal));
        } else {
            // Stack on entry: [..., receiver, value]
            int valueLocal = nextFreeLocal(m);
            int receiverLocal = valueLocal + valueType.getSize();
            m.maxLocals = Math.max(m.maxLocals, receiverLocal + 1);
            replacement.add(new VarInsnNode(valueType.getOpcode(Opcodes.ISTORE), valueLocal));
            replacement.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
            emitLogicLoad(replacement, cn, logicFieldName(fin.owner), bridgeInternal);
            replacement.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
            replacement.add(new VarInsnNode(valueType.getOpcode(Opcodes.ILOAD), valueLocal));
        }
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, bridgeInternal,
                bm.bridgeMethodName(), bridgeDesc, true));
        m.instructions.insertBefore(fin, replacement);
        m.instructions.remove(fin);
    }

    /**
     * Rewrite an {@code LDC T.class} of a mod-private target into a call to the bridge's
     * {@code _class()} accessor. The bridge impl is loaded by the per-mod ModClassLoader, so the
     * {@code T.class} reference inside the impl resolves there — and the mixin gets the real
     * {@code Class<T>} object (typed as {@code Class<?>} on the bridge interface).
     */
    private void rewriteClassLiteral(ClassNode cn, MethodNode m, LdcInsnNode ldc,
                                     String bridgeInternal) {
        InsnList replacement = new InsnList();
        Type t = (Type) ldc.cst;
        emitLogicLoad(replacement, cn, logicFieldName(t.getInternalName()), bridgeInternal);
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, bridgeInternal, "_class",
                "()Ljava/lang/Class;", true));
        m.instructions.insertBefore(ldc, replacement);
        m.instructions.remove(ldc);
    }

    /**
     * Estimate a safe starting local index past whatever the original method declared. We use
     * {@code maxLocals} as a high-water mark; even if the method has its own scratch slots, this
     * is past them.
     */
    private static int nextFreeLocal(MethodNode m) {
        return Math.max(m.maxLocals, paramLocalsTopOf(m));
    }

    private static int paramLocalsTopOf(MethodNode m) {
        int top = ((m.access & Opcodes.ACC_STATIC) == 0) ? 1 : 0;
        for (Type p : Type.getArgumentTypes(m.desc)) top += p.getSize();
        return top;
    }

    /**
     * Append the bytecode for "load LOGIC_* field, with self-init if null" to {@code target}.
     * Stack effect: pushes one bridge ref ({@code L<bridgeInternal>;}) onto the operand stack.
     *
     * <p>Replaces a plain {@code GETSTATIC <mixin>.LOGIC_X}. The plain form assumes the field has
     * already been populated by the mixin's synthetic {@code <clinit>}, which sponge-mixin merges
     * into the target's {@code <clinit>}. That works for ordinary mixin handlers (which fire well
     * after class init), but fails when the mixin uses {@code @Inject(at=HEAD)} on the target's
     * own {@code <clinit>}: the merged populator runs later in the same {@code <clinit>}, so the
     * HEAD handler sees a null field. The self-init pattern below makes every read robust to that
     * ordering — and to any other case where the field happens not to be populated yet (sponge
     * dropping the merge, an unrelated GETSTATIC firing during early bootstrap, etc).
     *
     * <p>Pattern:
     * <pre>
     *   GETSTATIC LOGIC
     *   DUP
     *   IFNONNULL hot
     *   POP
     *   LDC mixinFqn
     *   LDC fieldName
     *   INVOKESTATIC McdpProvider.resolveAutoBridgeImpl(String,String)Object
     *   CHECKCAST Bridge
     *   DUP
     *   PUTSTATIC LOGIC
     * hot:
     * </pre>
     *
     * <p>The cache write at the end is a fast-path for subsequent reads — McdpProvider's own
     * cache already serves repeated calls from a ConcurrentHashMap, but storing the cast result
     * locally avoids the static method dispatch + map lookup on the hot path.
     */
    private static void emitLogicLoad(InsnList target, ClassNode cn, String fieldName,
                                      String bridgeInternal) {
        String mixinDotted = BridgePolicy.toDotted(cn.name);
        String bridgeDesc = "L" + bridgeInternal + ";";
        LabelNode hot = new LabelNode();
        target.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, fieldName, bridgeDesc));
        target.add(new InsnNode(Opcodes.DUP));
        target.add(new JumpInsnNode(Opcodes.IFNONNULL, hot));
        target.add(new InsnNode(Opcodes.POP));
        target.add(new LdcInsnNode(mixinDotted));
        target.add(new LdcInsnNode(fieldName));
        target.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "de/lhns/mcdp/api/McdpProvider",
                "resolveAutoBridgeImpl",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
                false));
        target.add(new TypeInsnNode(Opcodes.CHECKCAST, bridgeInternal));
        target.add(new InsnNode(Opcodes.DUP));
        target.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, fieldName, bridgeDesc));
        target.add(hot);
    }

    public static String logicFieldName(String targetInternalName) {
        return "LOGIC_" + simpleName(targetInternalName).replace('$', '_');
    }

    public static String simpleName(String internalOrDotted) {
        String s = BridgePolicy.toDotted(internalOrDotted);
        int dot = s.lastIndexOf('.');
        return dot < 0 ? s : s.substring(dot + 1);
    }
}

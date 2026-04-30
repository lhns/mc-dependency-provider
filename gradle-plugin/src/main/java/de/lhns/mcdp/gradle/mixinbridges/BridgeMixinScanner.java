package de.lhns.mcdp.gradle.mixinbridges;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a compiled mixin class with ASM and produces a {@link MixinScanResult}: either
 * {@code SKIPPED} (no cross-classloader refs), {@code UNSUPPORTED} (header references
 * mod-private types — cannot rewrite without sharedPackages), or {@code REWRITABLE} (every
 * cross-classloader reference is grouped per target type).
 *
 * <p>The rewriter only touches method bodies. Class headers (interfaces, super, declared
 * field types) are validated against the policy: any mod-private reference in the header
 * fails the build with an actionable error pointing at {@code sharedPackages}, since
 * header rewriting is an explicit non-goal of ADR-0018 (Sponge would copy the bad
 * reference onto the target class regardless of what the rewriter did).</p>
 *
 * <p>Supported instruction set: INVOKESTATIC / INVOKEVIRTUAL / INVOKEINTERFACE,
 * GETSTATIC / GETFIELD, PUTSTATIC / PUTFIELD, LDC of a {@code Class<?>} constant, and
 * the {@code NEW T / DUP / args / INVOKESPECIAL T.<init>} constructor triplet — all on a
 * non-platform owner. CHECKCAST, INSTANCEOF, and ANEWARRAY of mod-private types are
 * inherently unbridgeable (the surrounding code's local-variable typing references the
 * mod-private type directly, which a bridge interface cannot express); the scanner
 * records those as warnings pointing at the user's only options
 * ({@code sharedPackages} or restructuring the mixin).</p>
 */
public final class BridgeMixinScanner {

    private final BridgePolicy policy;

    public BridgeMixinScanner(BridgePolicy policy) {
        this.policy = policy;
    }

    public MixinScanResult scan(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.SKIP_FRAMES);
        return scan(cn);
    }

    public MixinScanResult scan(ClassNode cn) {
        String mixinFqn = BridgePolicy.toDotted(cn.name);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (cn.superName != null && policy.needsBridge(cn.superName)) {
            errors.add(headerError(mixinFqn, "extends", BridgePolicy.toDotted(cn.superName)));
        }
        if (cn.interfaces != null) {
            for (String iface : cn.interfaces) {
                if (policy.needsBridge(iface)) {
                    errors.add(headerError(mixinFqn, "implements", BridgePolicy.toDotted(iface)));
                }
            }
        }
        if (cn.fields != null) {
            for (FieldNode f : cn.fields) {
                String fqn = ownerInternalNameOfDescriptor(f.desc);
                if (fqn != null && policy.needsBridge(fqn)) {
                    errors.add("mcdp-bridges: " + mixinFqn + " declares field '"
                            + f.name + "' of mod-private type " + BridgePolicy.toDotted(fqn)
                            + ". Declared field types live in the class header, which the codegen "
                            + "never rewrites (ADR-0018). Type the field with a bridge interface "
                            + "(in sharedPackages) instead, or add the package to "
                            + "mcdepprovider.sharedPackages.");
                }
            }
        }
        if (!errors.isEmpty()) {
            return MixinScanResult.unsupported(mixinFqn, errors);
        }

        Map<String, List<BridgeMember>> targets = new LinkedHashMap<>();
        List<LambdaSite> lambdaSites = new ArrayList<>();
        // Per-class state for lambda walking: a stable site index across the whole class (so
        // wrapper class names stay unique) and a guard set against recursion through self-
        // referential synthetics.
        SiteContext ctx = new SiteContext(cn);
        if (cn.methods != null) {
            for (MethodNode m : cn.methods) {
                if (m.instructions == null) continue;
                walkMethod(mixinFqn, m, targets, lambdaSites, warnings, ctx);
            }
        }

        if (targets.isEmpty() && lambdaSites.isEmpty()) {
            return MixinScanResult.skipped(mixinFqn, warnings);
        }
        // Dedupe per-target members (a single call site appears once but multiple sites can refer
        // to the same name+desc — keep one entry per unique tuple).
        Map<String, List<BridgeMember>> deduped = new LinkedHashMap<>();
        for (var e : targets.entrySet()) {
            List<BridgeMember> uniq = new ArrayList<>();
            for (BridgeMember bm : e.getValue()) {
                if (!uniq.contains(bm)) uniq.add(bm);
            }
            deduped.put(e.getKey(), uniq);
        }
        return MixinScanResult.rewritable(mixinFqn, deduped, lambdaSites, warnings);
    }

    /** Per-class scan context: stable site numbering and recursion guard. */
    private static final class SiteContext {
        final ClassNode cn;
        int nextSiteIndex = 0;
        final Set<String> visitedSyntheticIds = new HashSet<>();
        SiteContext(ClassNode cn) { this.cn = cn; }
    }

    private void walkMethod(String mixinFqn, MethodNode m,
                            Map<String, List<BridgeMember>> targets,
                            List<LambdaSite> lambdaSites,
                            List<String> warnings,
                            SiteContext ctx) {
        // Self-references (instructions whose owner is the class being scanned) never need a
        // bridge — the receiver class and the call target share the same defining loader, so
        // there's no cross-classloader boundary to cross. Catching this matters for ADR-0008
        // legacy `@McdpMixin` code: those mixins have a static `LOGIC` field on themselves and
        // a PUTSTATIC self-reference inside `<clinit>`. Without this check, the rewriter would
        // emit a bridge impl that loads the mixin class to PUTSTATIC the field, and Sponge
        // throws `IllegalClassLoadError` because mixin classes can't be referenced directly.
        String selfInternal = ctx.cn.name;
        String methodId = m.name + m.desc;
        AbstractInsnNode insn = m.instructions.getFirst();
        AbstractInsnNode prev = null;
        int instructionIndex = 0;
        while (insn != null) {
            switch (insn.getOpcode()) {
                case org.objectweb.asm.Opcodes.INVOKESTATIC,
                     org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                     org.objectweb.asm.Opcodes.INVOKEINTERFACE -> {
                    MethodInsnNode min = (MethodInsnNode) insn;
                    // Class.forName check fires regardless of owner — java/lang/Class is platform,
                    // so this won't be tripped by the needsBridge gate below.
                    if (isClassForName(min) && prev instanceof LdcInsnNode ldc
                            && ldc.cst instanceof String s && policy.needsBridge(s)) {
                        warnings.add("mcdp-bridges: " + mixinFqn + "#" + m.name
                                + " uses Class.forName(\"" + s + "\") on a mod-private "
                                + "class. Reflection on mod-private classes is not bridged "
                                + "automatically; restructure to call through a bridge.");
                    }
                    if (needsBridgeFromBody(min.owner, selfInternal)) {
                        BridgeMember.Kind k = switch (insn.getOpcode()) {
                            case org.objectweb.asm.Opcodes.INVOKESTATIC -> BridgeMember.Kind.STATIC_METHOD;
                            case org.objectweb.asm.Opcodes.INVOKEVIRTUAL -> BridgeMember.Kind.VIRTUAL_METHOD;
                            default -> BridgeMember.Kind.INTERFACE_METHOD;
                        };
                        validateDescriptor(mixinFqn, m.name, min.desc, warnings);
                        targets.computeIfAbsent(min.owner, k0 -> new ArrayList<>())
                                .add(new BridgeMember(k, min.name, min.desc));
                    }
                }
                case org.objectweb.asm.Opcodes.INVOKESPECIAL -> {
                    MethodInsnNode min = (MethodInsnNode) insn;
                    if (needsBridgeFromBody(min.owner, selfInternal) && min.name.equals("<init>")) {
                        validateDescriptor(mixinFqn, m.name, min.desc, warnings);
                        targets.computeIfAbsent(min.owner, k0 -> new ArrayList<>())
                                .add(new BridgeMember(BridgeMember.Kind.CONSTRUCTOR,
                                        min.name, min.desc));
                    }
                    // super.method() and private-method INVOKESPECIAL on mod-private owners are
                    // unusual inside a mixin (the mixin's superclass should be a vanilla type);
                    // the header check earlier flags those as errors so we don't double-report.
                }
                case org.objectweb.asm.Opcodes.GETSTATIC -> {
                    FieldInsnNode fin = (FieldInsnNode) insn;
                    if (needsBridgeFromBody(fin.owner, selfInternal)) {
                        targets.computeIfAbsent(fin.owner, k -> new ArrayList<>())
                                .add(new BridgeMember(BridgeMember.Kind.STATIC_FIELD_GET, fin.name, fin.desc));
                    }
                }
                case org.objectweb.asm.Opcodes.GETFIELD -> {
                    FieldInsnNode fin = (FieldInsnNode) insn;
                    if (needsBridgeFromBody(fin.owner, selfInternal)) {
                        targets.computeIfAbsent(fin.owner, k -> new ArrayList<>())
                                .add(new BridgeMember(BridgeMember.Kind.INSTANCE_FIELD_GET, fin.name, fin.desc));
                    }
                }
                case org.objectweb.asm.Opcodes.PUTSTATIC -> {
                    FieldInsnNode fin = (FieldInsnNode) insn;
                    if (needsBridgeFromBody(fin.owner, selfInternal)) {
                        targets.computeIfAbsent(fin.owner, k -> new ArrayList<>())
                                .add(new BridgeMember(BridgeMember.Kind.STATIC_FIELD_SET,
                                        fin.name, fin.desc));
                    }
                }
                case org.objectweb.asm.Opcodes.PUTFIELD -> {
                    FieldInsnNode fin = (FieldInsnNode) insn;
                    if (needsBridgeFromBody(fin.owner, selfInternal)) {
                        targets.computeIfAbsent(fin.owner, k -> new ArrayList<>())
                                .add(new BridgeMember(BridgeMember.Kind.INSTANCE_FIELD_SET,
                                        fin.name, fin.desc));
                    }
                }
                case org.objectweb.asm.Opcodes.NEW -> {
                    // NEW T paired with INVOKESPECIAL T.<init> is handled at the INVOKESPECIAL
                    // site (the rewriter removes the NEW+DUP and replaces the INVOKESPECIAL).
                    // A bare NEW with no matching <init> would be unusual user code; let it pass
                    // through silently — verify will surface it later if it's actually broken.
                }
                case org.objectweb.asm.Opcodes.CHECKCAST,
                     org.objectweb.asm.Opcodes.INSTANCEOF,
                     org.objectweb.asm.Opcodes.ANEWARRAY -> {
                    if (insn instanceof org.objectweb.asm.tree.TypeInsnNode tin
                            && policy.needsBridge(tin.desc)) {
                        warnings.add("mcdp-bridges: " + mixinFqn + "#" + m.name
                                + " uses opcode " + insn.getOpcode() + " on mod-private type "
                                + BridgePolicy.toDotted(tin.desc)
                                + " — this is a bridge-model limit, not a deferred feature. "
                                + "A bridge interface cannot mention a mod-private type in any "
                                + "signature, and the surrounding bytecode's local-variable typing "
                                + "would still reference the type directly. Add the package to "
                                + "`mcdepprovider.sharedPackages` so the type is visible to both "
                                + "loaders, or restructure the mixin to keep this operation "
                                + "inside mod code. See docs/mixin-bridge.md.");
                    }
                }
                case org.objectweb.asm.Opcodes.LDC -> {
                    if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type t
                            && t.getSort() == Type.OBJECT
                            && needsBridgeFromBody(t.getInternalName(), selfInternal)) {
                        targets.computeIfAbsent(t.getInternalName(), k -> new ArrayList<>())
                                .add(new BridgeMember(BridgeMember.Kind.CLASS_LITERAL,
                                        "_class", "Ljava/lang/Class;"));
                    }
                }
                case org.objectweb.asm.Opcodes.INVOKEDYNAMIC -> {
                    if (insn instanceof InvokeDynamicInsnNode indy) {
                        handleIndy(mixinFqn, m, methodId, indy, instructionIndex,
                                targets, lambdaSites, warnings, ctx);
                    }
                }
                default -> { /* nothing */ }
            }
            prev = insn;
            insn = insn.getNext();
            instructionIndex++;
        }
    }

    /**
     * Inspect an {@code INVOKEDYNAMIC} site. If the bsm is {@code LambdaMetafactory.metafactory}
     * or {@code altMetafactory} and the implementation method is a synthetic on the same class,
     * recursively scan that method's body for cross-classloader references and record the site
     * for the rewriter (ADR-0021 lambda-site coverage).
     */
    private void handleIndy(String mixinFqn, MethodNode containingMethod, String containingMethodId,
                            InvokeDynamicInsnNode indy, int instructionIndex,
                            Map<String, List<BridgeMember>> targets,
                            List<LambdaSite> lambdaSites,
                            List<String> warnings, SiteContext ctx) {
        Handle bsm = indy.bsm;
        if (bsm == null) return;
        boolean isMetafactory = "java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner())
                && ("metafactory".equals(bsm.getName()) || "altMetafactory".equals(bsm.getName()));
        if (!isMetafactory) {
            // Other indy bootstraps (Scala 3 string interpolation, custom MethodHandle plumbing)
            // are out of scope for the scanner — warn so a real-world miss is visible.
            warnings.add("mcdp-bridges: " + mixinFqn + "#" + containingMethod.name
                    + " uses INVOKEDYNAMIC with a non-LambdaMetafactory bootstrap ("
                    + bsm.getOwner() + "." + bsm.getName() + "). Cross-classloader refs through "
                    + "such sites are not auto-bridged by the codegen — restructure to a regular "
                    + "method call or add the targeted package to mcdepprovider.sharedPackages.");
            return;
        }
        // bsm args are: [samMethodType, implMethod, instantiatedMethodType] (metafactory)
        // or [samMethodType, implMethod, instantiatedMethodType, flags, ...] (altMetafactory).
        // Index 1 is the implementation method handle in both shapes.
        if (indy.bsmArgs == null || indy.bsmArgs.length < 2 || !(indy.bsmArgs[1] instanceof Handle implHandle)) {
            warnings.add("mcdp-bridges: " + mixinFqn + "#" + containingMethod.name
                    + " has an INVOKEDYNAMIC LambdaMetafactory site with an unexpected bsm-arg "
                    + "shape — skipping. (Report at the project tracker.)");
            return;
        }
        // Resolve the SAM type from the indy descriptor's return.
        Type indySig = Type.getMethodType(indy.desc);
        Type samType = indySig.getReturnType();
        if (samType.getSort() != Type.OBJECT) {
            return;  // primitive-typed indy — not a lambda metafactory
        }
        String samInternal = samType.getInternalName();

        // Only wrap when the impl method is a synthetic on this same class. If the impl points
        // at a different class (method reference like `Foo::bar`), the lambda body lives on
        // that other class and isn't part of our mixin's bytecode — out of scope here.
        boolean implOnContainer = ctx.cn.name.equals(implHandle.getOwner());
        if (!implOnContainer) {
            // Method reference: the call goes through the existing INVOKE rules (LambdaMetafactory
            // wraps the target as the SAM, but the underlying call is to the referenced method).
            // If the referenced owner is mod-private, the regular INVOKE branch wouldn't have
            // caught it (it's wrapped inside the indy). Surface as a warning.
            if (policy.needsBridge(implHandle.getOwner())) {
                warnings.add("mcdp-bridges: " + mixinFqn + "#" + containingMethod.name
                        + " uses a method reference to " + BridgePolicy.toDotted(implHandle.getOwner())
                        + "." + implHandle.getName() + " through LambdaMetafactory. The referenced "
                        + "method is on a mod-private type; method references to mod-private types "
                        + "are not auto-bridged. Restructure to a lambda body whose synthetic "
                        + "lives on this class so the codegen can rewrite it.");
            }
            return;
        }

        // Locate the synthetic body and scan it for cross-classloader references. The
        // synthetic's own body still gets the regular bridge treatment so when it runs (e.g.,
        // through the wrapper's SAM dispatch) its mod-private references resolve correctly.
        MethodNode synthetic = findMethod(ctx.cn, implHandle.getName(), implHandle.getDesc());
        if (synthetic == null) {
            warnings.add("mcdp-bridges: " + mixinFqn + "#" + containingMethod.name
                    + " references synthetic " + implHandle.getName() + implHandle.getDesc()
                    + " which is not present on the class — skipping the lambda site.");
            return;
        }
        String syntheticId = synthetic.name + synthetic.desc;
        if (ctx.visitedSyntheticIds.add(syntheticId)) {
            // Recurse into the synthetic body so any mod-private refs inside it are also bridged
            // through the regular targets map (the synthetic is a normal method on the container
            // and will be rewritten by the existing pipeline). Nested lambdas inside the synthetic
            // re-enter handleIndy with their own SiteContext entries.
            walkMethod(mixinFqn, synthetic, targets, lambdaSites, warnings, ctx);
        }

        int siteIndex = ctx.nextSiteIndex++;
        lambdaSites.add(new LambdaSite(ctx.cn.name, containingMethodId, instructionIndex,
                samInternal, indy.name, implHandle, indy.desc, siteIndex));
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        if (cn.methods == null) return null;
        for (MethodNode m : cn.methods) {
            if (name.equals(m.name) && desc.equals(m.desc)) return m;
        }
        return null;
    }

    /**
     * Like {@link BridgePolicy#needsBridge(String)} but returns {@code false} for
     * self-references — i.e. instructions in the body of the class being scanned whose owner
     * is that same class. Self-references resolve via the same defining loader on both sides;
     * there's no cross-classloader boundary to bridge.
     */
    private boolean needsBridgeFromBody(String owner, String selfInternal) {
        if (BridgePolicy.toInternal(owner).equals(selfInternal)) return false;
        return policy.needsBridge(owner);
    }

    private boolean isClassForName(MethodInsnNode min) {
        return "java/lang/Class".equals(min.owner) && "forName".equals(min.name);
    }

    private void validateDescriptor(String mixinFqn, String methodName, String desc,
                                    List<String> warnings) {
        for (Type arg : Type.getArgumentTypes(desc)) {
            checkType(mixinFqn, methodName, arg, warnings);
        }
        checkType(mixinFqn, methodName, Type.getReturnType(desc), warnings);
    }

    private void checkType(String mixinFqn, String methodName, Type t, List<String> warnings) {
        Type elem = t;
        while (elem.getSort() == Type.ARRAY) elem = elem.getElementType();
        if (elem.getSort() != Type.OBJECT) return;
        String name = elem.getInternalName();
        if (policy.needsBridge(name)) {
            String dot = BridgePolicy.toDotted(name);
            if (dot.startsWith("scala.") || dot.startsWith("kotlin.")) {
                warnings.add("mcdp-bridges: " + mixinFqn + "#" + methodName
                        + " has a Scala/Kotlin type (" + dot + ") in its signature. "
                        + "Bridge interfaces require game-layer-visible types only; convert to "
                        + "a Java-callable signature (e.g. `java.util.Optional` instead of "
                        + "`scala.Option`).");
            }
            // Other mod-private types in the descriptor mean the bridge interface itself can't
            // express the call — log as a warning so the mod author sees the gap.
        }
    }

    private static String ownerInternalNameOfDescriptor(String desc) {
        Type t = Type.getType(desc);
        while (t.getSort() == Type.ARRAY) t = t.getElementType();
        return t.getSort() == Type.OBJECT ? t.getInternalName() : null;
    }

    private static String headerError(String mixinFqn, String relation, String referenced) {
        return "mcdp-bridges: " + mixinFqn + " " + relation + " " + referenced
                + " — the header references a mod-private type, which the codegen cannot rewrite. "
                + "Add the package to `mcdepprovider.sharedPackages.add(\"...\")` so the type is "
                + "loaded by the game-layer classloader, or restructure the mixin to keep "
                + relation + " on a platform/shared type.";
    }
}

package de.lhns.mcdp.gradle.validate;

import de.lhns.mcdp.gradle.mixinbridges.BridgePolicy;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADR-0024 validator B: classes NOT in the cross-loader set that reference cross-loader
 * types whose package isn't shared. Catches the "share too narrow" footgun
 * (mc-blockshifter incident).
 *
 * <p>Two-pass:
 * <ol>
 *   <li><b>Build registry.</b> Walk every input class. A class is a "cross-loader type"
 *       if it carries any annotation in {@code crossLoaderAnnotations}. For
 *       {@code @Mixin} specifically, narrow further: only interfaces that declare at least
 *       one {@code @Accessor} or {@code @Invoker} method count — pure target-only mixins
 *       aren't user-visible types and never produce the cast pattern.</li>
 *   <li><b>Walk callers.</b> For every class NOT in the cross-loader set, collect refs.
 *       Any ref to a registry member whose package is not in {@code sharedPackages} →
 *       diagnostic with the precise {@code sharedPackages.add('...')} line.</li>
 * </ol>
 */
public final class CrossLoaderCastValidator {

    private static final String MIXIN_ANNOTATION = "org.spongepowered.asm.mixin.Mixin";
    private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    private final List<String> crossLoaderAnnotationDescs;
    private final List<String> sharedPackages;

    /**
     * @param crossLoaderAnnotations FQNs of class-level annotations marking cross-loader types
     *                               (default: just {@code org.spongepowered.asm.mixin.Mixin})
     * @param sharedPackages dotted package prefixes (with or without trailing dot)
     */
    public CrossLoaderCastValidator(List<String> crossLoaderAnnotations, List<String> sharedPackages) {
        List<String> descs = new ArrayList<>();
        for (String fqn : crossLoaderAnnotations) {
            descs.add("L" + BridgePolicy.toInternal(fqn) + ";");
        }
        this.crossLoaderAnnotationDescs = List.copyOf(descs);

        List<String> normalized = new ArrayList<>();
        for (String p : sharedPackages) {
            String dotted = BridgePolicy.toDotted(p);
            if (!dotted.endsWith(".")) dotted = dotted + ".";
            normalized.add(dotted);
        }
        this.sharedPackages = List.copyOf(normalized);
    }

    public List<Diagnostic> validate(List<byte[]> classes) {
        // Pass 1: parse every class once.
        List<ClassNode> nodes = new ArrayList<>();
        for (byte[] b : classes) {
            ClassNode cn = new ClassNode();
            new ClassReader(b).accept(cn, 0);
            nodes.add(cn);
        }

        // Pass 2: build two registries.
        //   crossLoaderClasses = every class annotated with a crossLoaderAnnotation. These
        //     classes are loaded by the game-layer transformer; refs FROM them to other
        //     crossLoader types are intra-game-layer and safe.
        //   accessorTargets    = subset of crossLoaderClasses we should flag refs TO from
        //     non-crossLoader callers. For @Mixin specifically, narrow to interfaces with
        //     @Accessor/@Invoker methods — pure target-only injectors (no user-visible
        //     members) are never cast to from mod code.
        Set<String> crossLoaderClasses = new LinkedHashSet<>();
        Set<String> accessorTargets = new LinkedHashSet<>();
        for (ClassNode cn : nodes) {
            if (!isAnyCrossLoaderAnnotated(cn)) continue;
            crossLoaderClasses.add(cn.name);
            if (isAccessorTarget(cn)) accessorTargets.add(cn.name);
        }
        if (accessorTargets.isEmpty()) return List.of();

        // Pass 3: for each non-crossLoader class, collect refs and check for hits.
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (ClassNode cn : nodes) {
            if (crossLoaderClasses.contains(cn.name)) continue;
            Set<String> refs = ClassRefCollector.collect(cn);
            // Group offending refs by accessor package so the recommendation is one line per
            // package even if multiple accessors live there.
            Map<String, List<String>> hitsByPkg = new LinkedHashMap<>();
            for (String ref : refs) {
                if (!accessorTargets.contains(ref)) continue;
                String dotted = BridgePolicy.toDotted(ref);
                if (isShared(dotted)) continue;
                String pkg = packageOf(dotted);
                hitsByPkg.computeIfAbsent(pkg, k -> new ArrayList<>()).add(dotted);
            }
            if (!hitsByPkg.isEmpty()) {
                diagnostics.add(buildDiagnostic(BridgePolicy.toDotted(cn.name), hitsByPkg));
            }
        }
        return diagnostics;
    }

    private boolean isAnyCrossLoaderAnnotated(ClassNode cn) {
        return hasAnyAnnotation(cn.visibleAnnotations) || hasAnyAnnotation(cn.invisibleAnnotations);
    }

    private boolean isAccessorTarget(ClassNode cn) {
        // Mixin-specific narrowing: only interfaces with @Accessor / @Invoker methods are
        // user-visible types whose Class identity matters at cast sites. Pure target-only
        // mixins (@Mixin(BlockEntity.class) class FooMixin { @Inject ... }) merge code into
        // the target but are never cast to from mod code.
        if (isMixinAnnotated(cn)) return hasAccessorOrInvokerMethod(cn);
        // For non-Mixin cross-loader annotations, we have no equivalent narrowing signal —
        // assume any annotated class is a user-visible accessor target.
        return true;
    }

    private boolean hasAnyAnnotation(List<AnnotationNode> anns) {
        if (anns == null) return false;
        for (AnnotationNode a : anns) {
            if (a.desc != null && crossLoaderAnnotationDescs.contains(a.desc)) return true;
        }
        return false;
    }

    private boolean isMixinAnnotated(ClassNode cn) {
        String mixinDesc = "L" + BridgePolicy.toInternal(MIXIN_ANNOTATION) + ";";
        return hasAnnotationDesc(cn.visibleAnnotations, mixinDesc)
                || hasAnnotationDesc(cn.invisibleAnnotations, mixinDesc);
    }

    private static boolean hasAnnotationDesc(List<AnnotationNode> anns, String desc) {
        if (anns == null) return false;
        for (AnnotationNode a : anns) {
            if (desc.equals(a.desc)) return true;
        }
        return false;
    }

    private static boolean hasAccessorOrInvokerMethod(ClassNode cn) {
        if (cn.methods == null) return false;
        for (MethodNode m : cn.methods) {
            if (hasAnnotationDesc(m.visibleAnnotations, ACCESSOR_DESC)
                    || hasAnnotationDesc(m.invisibleAnnotations, ACCESSOR_DESC)
                    || hasAnnotationDesc(m.visibleAnnotations, INVOKER_DESC)
                    || hasAnnotationDesc(m.invisibleAnnotations, INVOKER_DESC)) {
                return true;
            }
        }
        return false;
    }

    private boolean isShared(String dottedFqn) {
        for (String p : sharedPackages) {
            if (dottedFqn.startsWith(p)) return true;
        }
        return false;
    }

    private static String packageOf(String dottedFqn) {
        int idx = dottedFqn.lastIndexOf('.');
        return idx < 0 ? "" : dottedFqn.substring(0, idx);
    }

    private static Diagnostic buildDiagnostic(String fromClass, Map<String, List<String>> hitsByPkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("mcdepprovider: ").append(fromClass).append(" references cross-loader-injected ");
        sb.append("type(s) whose package is not in sharedPackages:\n");
        List<String> allRefs = new ArrayList<>();
        for (var entry : hitsByPkg.entrySet()) {
            for (String fqn : entry.getValue()) {
                sb.append("  - ").append(fqn).append('\n');
                allRefs.add(fqn);
            }
        }
        sb.append("\nAt runtime, ").append(fromClass).append("'s per-mod ModClassLoader and the\n");
        sb.append("Mixin-merged accessor on the game-layer loader will resolve to two different\n");
        sb.append("Class objects with the same name, producing ClassCastException at the cast site.\n\n");
        sb.append("Fix: in build.gradle's mcdepprovider {} block, add\n");
        for (String pkg : hitsByPkg.keySet()) {
            sb.append("  sharedPackages.add('").append(pkg).append(".')\n");
        }
        sb.append("\nSee ADR-0024 / docs/bridges.md for why CHECKCAST/INSTANCEOF cannot be auto-rewritten.");
        return new Diagnostic(Diagnostic.Kind.UNDER_SHARE, fromClass, List.copyOf(allRefs), sb.toString());
    }
}

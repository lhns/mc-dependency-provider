package de.lhns.mcdp.gradle.validate;

import de.lhns.mcdp.gradle.mixinbridges.BridgePolicy;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ADR-0024 validator A: classes IN {@code sharedPackages} that reference types not visible
 * to the platform classloader. Catches the "share too coarse" footgun
 * (mc-game-of-life-3d incident).
 *
 * <p>For each input class whose dotted package matches one of the {@code sharedPackages}
 * prefixes, collect every referenced FQN and flag any whose package is:
 * <ul>
 *   <li>not a {@link BridgePolicy#PLATFORM_PREFIXES platform prefix},</li>
 *   <li>not itself in {@code sharedPackages},</li>
 *   <li>not the mod's own bridge package or any other package the user declared as shared.</li>
 * </ul>
 * Such a reference will hit {@code NoClassDefFoundError} when the shared class is loaded
 * by the platform classloader, since the mod's per-mod {@code ModClassLoader} URLs are not
 * reachable from there.
 */
public final class SharedPackageContentValidator {

    private final List<String> sharedPackages;

    /**
     * @param sharedPackages dotted package prefixes (with or without trailing dot;
     *                       both accepted, normalized internally to dotted-with-dot)
     */
    public SharedPackageContentValidator(List<String> sharedPackages) {
        List<String> normalized = new ArrayList<>();
        for (String p : sharedPackages) {
            String dotted = BridgePolicy.toDotted(p);
            if (!dotted.endsWith(".")) dotted = dotted + ".";
            normalized.add(dotted);
        }
        this.sharedPackages = List.copyOf(normalized);
    }

    /**
     * @return diagnostics for the given class (empty if none).
     */
    public List<Diagnostic> validate(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        return validate(cn);
    }

    public List<Diagnostic> validate(ClassNode cn) {
        String dottedFqn = BridgePolicy.toDotted(cn.name);
        if (!isInSharedPackage(dottedFqn)) return List.of();

        Set<String> refs = ClassRefCollector.collect(cn);
        List<String> offending = new ArrayList<>();
        for (String ref : refs) {
            String dotted = BridgePolicy.toDotted(ref);
            if (dotted.equals(dottedFqn)) continue;
            if (isVisibleFromPlatformLoader(dotted)) continue;
            offending.add(dotted);
        }
        if (offending.isEmpty()) return List.of();
        return List.of(buildDiagnostic(dottedFqn, offending));
    }

    private boolean isInSharedPackage(String dottedFqn) {
        for (String p : sharedPackages) {
            if (dottedFqn.startsWith(p)) return true;
        }
        return false;
    }

    private boolean isVisibleFromPlatformLoader(String dottedFqn) {
        for (String p : BridgePolicy.PLATFORM_PREFIXES) {
            if (dottedFqn.startsWith(p)) return true;
        }
        for (String p : sharedPackages) {
            if (dottedFqn.startsWith(p)) return true;
        }
        return false;
    }

    private static Diagnostic buildDiagnostic(String fromClass, List<String> offending) {
        StringBuilder sb = new StringBuilder();
        sb.append("mcdepprovider: shared-package class ").append(fromClass);
        sb.append(" references ").append(offending.size() == 1 ? "type" : "types").append(" not visible to the platform classloader:\n");
        for (String o : offending) sb.append("  - ").append(o).append('\n');
        sb.append("Sharing this class moves it onto the platform/parent classloader, where the mod's\n");
        sb.append("per-mod ModClassLoader URLs are not reachable. The references above will fail with\n");
        sb.append("NoClassDefFoundError at the first static-init or first use of the shared class.\n");
        sb.append("Fix: narrow sharedPackages to a sibling package containing only the bridge type\n");
        sb.append("(e.g. extract the cross-loader-visible interface into its own package), or move\n");
        sb.append("the offending class out of the shared package. See ADR-0024 / docs/bridges.md.");
        return new Diagnostic(Diagnostic.Kind.OVER_SHARE, fromClass, List.copyOf(offending), sb.toString());
    }
}

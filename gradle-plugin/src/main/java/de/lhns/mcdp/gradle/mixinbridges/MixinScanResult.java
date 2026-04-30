package de.lhns.mcdp.gradle.mixinbridges;

import java.util.List;
import java.util.Map;

/**
 * Output of {@link BridgeMixinScanner} for a single mixin class. Either:
 * <ul>
 *   <li>{@code skipped}: the class was inspected but doesn't need any bridge work (no
 *       cross-classloader references in any method body).</li>
 *   <li>{@code unsupported}: the class declares a non-platform interface, super, or field. The
 *       codegen refuses to rewrite the class — the mod author must add the offending package to
 *       {@code sharedPackages} or restructure the mixin.</li>
 *   <li>{@code rewritable}: a non-empty {@link #targets()} map describes per-target-type the
 *       set of {@link BridgeMember}s the rewriter must dispatch through bridges. Optionally,
 *       {@link #lambdaSites()} lists {@code INVOKEDYNAMIC} sites whose implementation method is
 *       a synthetic lambda body containing cross-classloader references — the rewriter replaces
 *       these with constructor calls to generated wrapper classes (ADR-0021).</li>
 * </ul>
 */
public final class MixinScanResult {

    public enum Status { SKIPPED, UNSUPPORTED, REWRITABLE }

    private final String mixinFqn;
    private final Status status;
    private final List<String> errors;
    private final List<String> warnings;
    /** target internal name → bridge member set */
    private final Map<String, List<BridgeMember>> targets;
    /** All lambda sites discovered across the class's methods. */
    private final List<LambdaSite> lambdaSites;

    private MixinScanResult(String mixinFqn, Status status, List<String> errors,
                            List<String> warnings, Map<String, List<BridgeMember>> targets,
                            List<LambdaSite> lambdaSites) {
        this.mixinFqn = mixinFqn;
        this.status = status;
        this.errors = List.copyOf(errors);
        this.warnings = List.copyOf(warnings);
        this.targets = Map.copyOf(targets);
        this.lambdaSites = List.copyOf(lambdaSites);
    }

    public static MixinScanResult skipped(String fqn, List<String> warnings) {
        return new MixinScanResult(fqn, Status.SKIPPED, List.of(), warnings, Map.of(), List.of());
    }

    public static MixinScanResult unsupported(String fqn, List<String> errors) {
        return new MixinScanResult(fqn, Status.UNSUPPORTED, errors, List.of(), Map.of(), List.of());
    }

    public static MixinScanResult rewritable(String fqn, Map<String, List<BridgeMember>> targets,
                                              List<String> warnings) {
        return new MixinScanResult(fqn, Status.REWRITABLE, List.of(), warnings, targets, List.of());
    }

    public static MixinScanResult rewritable(String fqn, Map<String, List<BridgeMember>> targets,
                                              List<LambdaSite> lambdaSites,
                                              List<String> warnings) {
        return new MixinScanResult(fqn, Status.REWRITABLE, List.of(), warnings, targets, lambdaSites);
    }

    public String mixinFqn() { return mixinFqn; }
    public Status status() { return status; }
    public List<String> errors() { return errors; }
    public List<String> warnings() { return warnings; }
    public Map<String, List<BridgeMember>> targets() { return targets; }
    public List<LambdaSite> lambdaSites() { return lambdaSites; }
}

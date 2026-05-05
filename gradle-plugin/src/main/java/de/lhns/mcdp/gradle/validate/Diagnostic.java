package de.lhns.mcdp.gradle.validate;

import java.util.List;

/**
 * One diagnostic emitted by the sharedPackages validators (ADR-0024). Two flavors:
 * <ul>
 *   <li>{@link Kind#OVER_SHARE} — a class in a {@code sharedPackages} entry references a
 *       type that won't resolve when the class is loaded by the platform classloader.</li>
 *   <li>{@link Kind#UNDER_SHARE} — a non-annotated mod class references a type marked as
 *       cross-loader-injected (e.g. a Mixin accessor) whose package isn't shared, so the
 *       reference will resolve to a different {@code Class} object than Mixin merged.</li>
 * </ul>
 * Both flavors carry a precise, copy-pasteable remediation in {@link #message()}.
 */
public record Diagnostic(
        Kind kind,
        String fromClass,
        List<String> offendingRefs,
        String message
) {
    public enum Kind { OVER_SHARE, UNDER_SHARE }
}

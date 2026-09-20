package de.lhns.mcdp.gradle.bridges;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Default-bridge policy: a reference is treated as cross-classloader (i.e. needs bridging)
 * unless we can prove it's safe — its FQN starts with a known platform prefix, lives in the
 * user's {@code sharedPackages}, or lives in the codegen's own bridge package.
 *
 * <p>The prefix list is {@code de.lhns.mcdp.core.ModClassLoader}'s {@code PLATFORM_PREFIXES}
 * <em>plus</em> the Mixin runtime prefix ({@code org.spongepowered.asm.mixin.}), which the
 * codegen needs treated as parent-first even though the runtime loader doesn't list it. It is
 * duplicated inline because {@code :gradle-plugin} deliberately has no dependency on
 * {@code :core} — a Gradle plugin must not drag the runtime (and its Minecraft-facing
 * classpath) into the build classpath. {@code BridgePolicyPrefixParityTest} guards the
 * duplication against drift.</p>
 *
 * <p>FQNs are accepted in either dotted ({@code com.example.Foo}) or JVM-internal
 * ({@code com/example/Foo}) form; both are normalized internally.</p>
 */
public final class BridgePolicy {

    /** {@code ModClassLoader.PLATFORM_PREFIXES} plus the Mixin runtime prefix (dotted form). */
    public static final List<String> PLATFORM_PREFIXES = List.of(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "org.w3c.",
            "org.xml.",
            "net.minecraft.",
            "net.neoforged.",
            "net.fabricmc.",
            "com.mojang.",
            "cpw.mods.",
            "org.slf4j.",
            "org.apache.logging.log4j.",
            "de.lhns.mcdp.api.",
            "de.lhns.mcdp.core.",
            "org.spongepowered.asm.mixin."
    );

    private final List<String> sharedPackages;
    private final String bridgePackageDot;

    public BridgePolicy(List<String> sharedPackages, String bridgePackage) {
        this.sharedPackages = normalizeSharedPackages(sharedPackages);
        this.bridgePackageDot = Objects.requireNonNull(bridgePackage) + ".";
    }

    /**
     * Canonical {@code sharedPackages} normalization for the whole plugin: dotted form with a
     * trailing dot, order and duplicates preserved. Every prefix match in this plugin is a raw
     * {@code startsWith}, so the trailing dot is what stops a bare {@code "com.example.api"}
     * entry from also swallowing {@code com.example.apiextra.Foo}; the slash translation is what
     * lets a user write the internal form {@code "com/example/api/"}.
     *
     * <p>{@code de.lhns.mcdp.core.ModClassLoader} applies the equivalent rule at runtime but
     * keeps its own copy, because {@code :gradle-plugin} must not depend on {@code :core} (see
     * the class javadoc). {@code SharedPackagePrefixParityTest} guards that pair against drift —
     * including the one place they differ on purpose: the runtime drops {@code null}/blank
     * entries, this one does not.</p>
     */
    public static List<String> normalizeSharedPackages(List<String> sharedPackages) {
        List<String> normalized = new ArrayList<>(sharedPackages.size());
        for (String p : sharedPackages) {
            String dotted = toDotted(p);
            normalized.add(dotted.endsWith(".") ? dotted : dotted + ".");
        }
        return List.copyOf(normalized);
    }

    /**
     * @return {@code true} if the named class needs to go through a bridge.
     */
    public boolean needsBridge(String anyForm) {
        String dot = toDotted(anyForm);
        for (String p : PLATFORM_PREFIXES) {
            if (dot.startsWith(p)) return false;
        }
        for (String p : sharedPackages) {
            if (dot.startsWith(p)) return false;
        }
        if (dot.startsWith(bridgePackageDot)) return false;
        return true;
    }

    public static String toDotted(String name) {
        return name.replace('/', '.');
    }

    public static String toInternal(String name) {
        return name.replace('.', '/');
    }
}

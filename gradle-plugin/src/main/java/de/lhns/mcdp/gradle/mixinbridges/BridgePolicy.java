package de.lhns.mcdp.gradle.mixinbridges;

import java.util.List;
import java.util.Objects;

/**
 * Default-bridge policy: a reference is treated as cross-classloader (i.e. needs bridging)
 * unless we can prove it's safe — its FQN starts with a known platform prefix, lives in the
 * user's {@code sharedPackages}, or lives in the codegen's own bridge package.
 *
 * <p>Mirrors {@code de.lhns.mcdp.core.ModClassLoader}'s {@code PLATFORM_PREFIXES}. Kept inline
 * so the gradle-plugin module doesn't pull a runtime dependency on {@code core}.</p>
 *
 * <p>FQNs are accepted in either dotted ({@code com.example.Foo}) or JVM-internal
 * ({@code com/example/Foo}) form; both are normalized internally.</p>
 */
public final class BridgePolicy {

    /** Mirror of {@link de.lhns.mcdp.core.ModClassLoader#PLATFORM_PREFIXES} (kept in dotted form). */
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
            "org.spongepowered.asm.mixin.",
            "org.spongepowered.asm.mixin.injection."
    );

    private final List<String> sharedPackages;
    private final String bridgePackageDot;

    public BridgePolicy(List<String> sharedPackages, String bridgePackage) {
        // Normalize incoming sharedPackages to dotted form so the matcher can compare uniformly,
        // regardless of whether the user wrote {@code "com.example.api"} (typical) or
        // {@code "com/example/api/"} (internal-form). Trailing dot is normalized too — the
        // ModClassLoader treats prefix entries as a {@code startsWith} test, and we mirror that
        // here.
        List<String> normalized = new java.util.ArrayList<>();
        for (String p : sharedPackages) {
            normalized.add(toDotted(p));
        }
        this.sharedPackages = List.copyOf(normalized);
        this.bridgePackageDot = Objects.requireNonNull(bridgePackage) + ".";
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

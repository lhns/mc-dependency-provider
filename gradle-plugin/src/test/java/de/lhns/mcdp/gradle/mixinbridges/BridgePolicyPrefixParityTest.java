package de.lhns.mcdp.gradle.mixinbridges;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift guard for {@link BridgePolicy#PLATFORM_PREFIXES}. The runtime loader
 * ({@code de.lhns.mcdp.core.ModClassLoader}) owns the canonical platform-prefix list, but
 * {@code :gradle-plugin} deliberately has no dependency on {@code :core}, so the list is
 * duplicated in {@code BridgePolicy}. If the two drift, the codegen bridges (or fails to
 * bridge) classes the runtime loader treats the other way round.
 *
 * <p>The expectation is hard-coded below rather than parsed out of
 * {@code core/src/main/java/de/lhns/mcdp/core/ModClassLoader.java}. Source parsing was the
 * alternative, but it is neither simpler nor more robust here: the core sources are not on the
 * test classpath, so the test would have to guess a path relative to the Gradle working
 * directory and re-implement a tolerant Java-literal scanner — both of which break for reasons
 * that have nothing to do with the invariant under test. A literal list fails loudly and
 * points straight at the file to re-sync. Reflection is out for the same reason as the
 * dependency: the class simply isn't there (and its field is package-private besides).
 *
 * <p><b>Keep in sync with</b> {@code core/src/main/java/de/lhns/mcdp/core/ModClassLoader.java},
 * field {@code PLATFORM_PREFIXES}.
 */
class BridgePolicyPrefixParityTest {

    /** Verbatim copy of {@code ModClassLoader.PLATFORM_PREFIXES}. */
    private static final List<String> MOD_CLASS_LOADER_PLATFORM_PREFIXES = List.of(
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
            "de.lhns.mcdp.core."
    );

    /**
     * The one prefix {@code BridgePolicy} carries beyond the runtime list: Mixin's own runtime
     * types must resolve parent-first for the bridge policy to make sense.
     */
    private static final List<String> EXPECTED_EXTRAS = List.of("org.spongepowered.asm.mixin.");

    @Test
    void containsEveryModClassLoaderPrefix() {
        List<String> missing = new ArrayList<>(MOD_CLASS_LOADER_PLATFORM_PREFIXES);
        missing.removeAll(BridgePolicy.PLATFORM_PREFIXES);
        assertTrue(missing.isEmpty(),
                "BridgePolicy.PLATFORM_PREFIXES is missing entries present in "
                        + "ModClassLoader.PLATFORM_PREFIXES: " + missing);
    }

    @Test
    void carriesOnlyTheDocumentedExtras() {
        List<String> extras = new ArrayList<>(BridgePolicy.PLATFORM_PREFIXES);
        extras.removeAll(MOD_CLASS_LOADER_PLATFORM_PREFIXES);
        assertEquals(EXPECTED_EXTRAS, extras,
                "BridgePolicy.PLATFORM_PREFIXES grew (or lost) an entry that ModClassLoader "
                        + "doesn't have. Either re-sync the two lists or document the new extra "
                        + "in BridgePolicy's javadoc and here.");
    }

    @Test
    void noPrefixIsShadowedByAnother() {
        for (String outer : BridgePolicy.PLATFORM_PREFIXES) {
            for (String inner : BridgePolicy.PLATFORM_PREFIXES) {
                if (outer.equals(inner)) continue;
                assertTrue(!inner.startsWith(outer),
                        "'" + inner + "' is dead: the startsWith test on '" + outer
                                + "' already covers it.");
            }
        }
    }
}

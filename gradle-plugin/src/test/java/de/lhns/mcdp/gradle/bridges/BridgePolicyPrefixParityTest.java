package de.lhns.mcdp.gradle.bridges;

import de.lhns.mcdp.gradle.testfixtures.RuntimeLoaderSource;
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
 * <p>The comparison reads the runtime's field for real, via
 * {@link RuntimeLoaderSource#platformPrefixes()} — see that class for why it compiles core's
 * source instead of depending on {@code :core}. An earlier version of this test compared
 * against a hand-typed copy of the list, which meant it could only ever detect edits to
 * {@code BridgePolicy}: the drift it is named for went straight through it.
 */
class BridgePolicyPrefixParityTest {

    /** The runtime's own {@code PLATFORM_PREFIXES}, read out of {@code :core}'s real source. */
    private static final List<String> MOD_CLASS_LOADER_PLATFORM_PREFIXES =
            RuntimeLoaderSource.platformPrefixes();

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

package de.lhns.mcdp.gradle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The default bridge package is emitted verbatim into generated sources, so every segment has to
 * be a legal Java identifier. Project names in this repo routinely carry dots and hyphens.
 */
class DefaultBridgePackageTest {

    @Test
    void sanitizesHyphens() {
        assertEquals("com.example.my_mod.mcdp_bridges",
                McdpProviderPlugin.defaultBridgePackage("com.example", "my-mod"));
    }

    @Test
    void sanitizesDottedVersionSuffix() {
        // Was `com.example.forge_example_1.20.mcdp_bridges` — segment `20` is not an identifier.
        assertEquals("com.example.forge_example_1_20.mcdp_bridges",
                McdpProviderPlugin.defaultBridgePackage("com.example", "forge-example-1.20"));
    }

    @Test
    void prefixesLeadingDigitSegments() {
        assertEquals("com.example._26_2.mcdp_bridges",
                McdpProviderPlugin.defaultBridgePackage("com.example", "26.2"));
    }

    @Test
    void keepsGroupSegmentsSeparate() {
        assertEquals("de.lhns.mcdp.core.mcdp_bridges",
                McdpProviderPlugin.defaultBridgePackage("de.lhns.mcdp", "core"));
    }

    @Test
    void emptyWhenGroupUnset() {
        assertEquals("", McdpProviderPlugin.defaultBridgePackage("unspecified", "my-mod"));
        assertEquals("", McdpProviderPlugin.defaultBridgePackage("", "my-mod"));
        assertEquals("", McdpProviderPlugin.defaultBridgePackage(null, "my-mod"));
    }
}

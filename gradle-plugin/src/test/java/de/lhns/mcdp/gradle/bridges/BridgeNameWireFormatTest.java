package de.lhns.mcdp.gradle.bridges;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Literal pin on the generated-name scheme, mirroring
 * {@code LambdaWrapperEmitterTest#logicFieldNameIsStable}.
 *
 * <p>These names are a wire format, not an implementation detail: the {@code <clinit>} the
 * plugin emits at build time hard-codes the {@code LOGIC_*} field name, and
 * {@code McdpProvider.AUTO_BRIDGE_REGISTRY} looks it up at runtime — possibly from a
 * <em>different</em> mcdp version than the one that built the mod. Every other assertion on
 * these names re-derives the expectation from the same helper, so a scheme change stays green
 * everywhere and breaks only at runtime in the field. Hence literals.</p>
 */
class BridgeNameWireFormatTest {

    @Test
    void bridgeSimpleNameIsStable() {
        assertEquals("Config_e42341", BridgeRewriter.bridgeSimpleName("com/mod/a/Config"));
        assertEquals("Level_31ab35",
                BridgeRewriter.bridgeSimpleName("net/minecraft/world/level/Level"));
    }

    @Test
    void acceptsInternalAndDottedForms() {
        assertEquals(BridgeRewriter.bridgeSimpleName("com/mod/a/Config"),
                BridgeRewriter.bridgeSimpleName("com.mod.a.Config"));
        assertEquals("Config_e42341", BridgeRewriter.bridgeSimpleName("com.mod.a.Config"));
    }

    @Test
    void packageQualificationDistinguishesSameSimpleName() {
        // The whole point of the hash suffix: com.mod.a.Config and com.mod.b.Config must not
        // both become ConfigBridge / LOGIC_Config.
        assertEquals("Config_e42341", BridgeRewriter.bridgeSimpleName("com/mod/a/Config"));
        assertEquals("Config_683231", BridgeRewriter.bridgeSimpleName("com/mod/b/Config"));
        assertNotEquals(BridgeRewriter.bridgeSimpleName("com/mod/a/Config"),
                BridgeRewriter.bridgeSimpleName("com/mod/b/Config"));
    }

    @Test
    void defaultPackageKeepsTheHash() {
        assertEquals("Config_87e89a", BridgeRewriter.bridgeSimpleName("Config"));
    }

    @Test
    void nestedClassesFlattenDollarToUnderscore() {
        // '$' is legal in a field name but not in a readable one; the hash still covers the
        // full dotted name including the '$' separators.
        assertEquals("Outer_Inner_eb5191", BridgeRewriter.bridgeSimpleName("com/example/Outer$Inner"));
        assertEquals("D_E_F_6063f1", BridgeRewriter.bridgeSimpleName("a/b/c/D$E$F"));
    }

    @Test
    void hashIsSixLowercaseHexDigits() {
        // Digest length is part of the wire format: widening or narrowing it renames every
        // field and bridge class emitted by this version.
        for (String n : new String[]{
                "com/mod/a/Config", "Config", "com/example/Outer$Inner",
                "net/minecraft/world/level/Level"}) {
            String stem = BridgeRewriter.bridgeSimpleName(n);
            String hash = stem.substring(stem.lastIndexOf('_') + 1);
            assertTrue(hash.matches("[0-9a-f]{6}"), n + " -> " + stem);
        }
    }

    @Test
    void logicFieldNameIsStable() {
        assertEquals("LOGIC_Config_e42341", BridgeRewriter.logicFieldName("com/mod/a/Config"));
        assertEquals("LOGIC_Config_e42341", BridgeRewriter.logicFieldName("com.mod.a.Config"));
        assertEquals("LOGIC_Outer_Inner_eb5191",
                BridgeRewriter.logicFieldName("com/example/Outer$Inner"));
        assertEquals("LOGIC_Level_31ab35",
                BridgeRewriter.logicFieldName("net/minecraft/world/level/Level"));
    }
}

package de.lhns.mcdp.gradle.validate;

import de.lhns.mcdp.gradle.bridges.BridgePolicy;
import de.lhns.mcdp.gradle.testfixtures.RuntimeLoaderSource;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sharedPackages} entries are prefix-matched in four places: the runtime
 * {@code ModClassLoader}, {@link BridgePolicy} (decides what the codegen bridges),
 * {@link SharedPackageContentValidator} and {@link CrossLoaderCastValidator}. All four must
 * apply the same rule — normalize to dotted form with a trailing dot, then {@code startsWith} —
 * or the build validates one package set while the codegen bridges another and the runtime
 * delegates a third.
 *
 * <p>The three plugin-side sites now share {@link BridgePolicy#normalizeSharedPackages}. The
 * runtime keeps its own copy, because {@code :gradle-plugin} deliberately has no dependency on
 * {@code :core} — a Gradle plugin must not drag the runtime's Minecraft-facing classpath into
 * the build classpath. The parity tests below stop the two copies from drifting apart by running
 * the runtime's real rule through {@link RuntimeLoaderSource}, which compiles core's source
 * rather than depending on {@code :core}.
 *
 * <p>The trailing dot is the part that used to differ: {@code BridgePolicy} normalized slashes
 * only, so a bare {@code "com.example.api"} entry also swallowed the sibling package
 * {@code com.example.apiextra} and the codegen skipped bridging refs the runtime loads
 * child-first.
 */
class SharedPackagePrefixParityTest {

    private static final String SIBLING = "com.example.apiextra.Neighbour";
    private static final String INSIDE = "com.example.api.Exposed";

    @Test
    void bridgePolicyAcceptsEveryEntrySpelling() {
        for (String spelling : List.of("com.example.api", "com.example.api.", "com/example/api/")) {
            BridgePolicy policy = new BridgePolicy(List.of(spelling), "com.example.mod.mcdp_bridges");
            assertFalse(policy.needsBridge(INSIDE),
                    "entry " + spelling + " should cover " + INSIDE);
            assertFalse(policy.needsBridge(BridgePolicy.toInternal(INSIDE)),
                    "entry " + spelling + " should cover the internal form of " + INSIDE);
        }
    }

    @Test
    void bridgePolicyDoesNotSwallowSiblingPackage() {
        BridgePolicy policy = new BridgePolicy(List.of("com.example.api"), "com.example.mod.mcdp_bridges");
        assertTrue(policy.needsBridge(SIBLING),
                SIBLING + " is not in com.example.api — a bare entry must not match it by "
                        + "prefix, or the codegen skips a bridge the runtime needs.");
    }

    @Test
    void contentValidatorDoesNotSwallowSiblingPackage() {
        SharedPackageContentValidator validator =
                new SharedPackageContentValidator(List.of("com.example.api"));
        // The sibling class references scala.Option. It is NOT in a shared package, so the
        // over-share validator must stay silent — same prefix rule as BridgePolicy.
        assertTrue(validator.validate(classRefingScala("com/example/apiextra/Neighbour")).isEmpty());
        assertFalse(validator.validate(classRefingScala("com/example/api/Exposed")).isEmpty(),
                "a class genuinely inside the shared package must still be flagged");
    }

    /**
     * The runtime rule, executed rather than transcribed: {@link RuntimeLoaderSource} compiles
     * core's real {@code ModClassLoader} source and runs its normalization. This used to be a
     * hand-copied body, which made every assertion below a comparison of the plugin against the
     * test's own idea of the runtime — deleting the runtime's blank-entry guard changed nothing
     * here.
     */
    private static List<String> modClassLoaderRule(List<String> raw) {
        return RuntimeLoaderSource.normalizePrefixes(raw);
    }

    /**
     * Dotted entries are the only spelling the runtime ever sees — they arrive from the generated
     * manifest, which the plugin writes in dotted form. For those, the two rules must agree
     * exactly, including order and duplicates.
     */
    @Test
    void pluginHelperMatchesRuntimeRuleForDottedEntries() {
        List<String> entries = List.of(
                "com.example.api",
                "com.example.api.",
                "com.example.api",
                "a",
                "com.example.mod.mcdp_bridges.",
                "de.lhns.mcdp.api.");
        assertEquals(modClassLoaderRule(entries),
                BridgePolicy.normalizeSharedPackages(entries),
                "BridgePolicy.normalizeSharedPackages drifted from ModClassLoader.normalizePrefixes");
        // Per-entry too, so a failure names the entry rather than a whole list diff.
        for (String e : entries) {
            assertEquals(modClassLoaderRule(List.of(e)),
                    BridgePolicy.normalizeSharedPackages(List.of(e)), "entry " + e);
        }
    }

    /**
     * The two documented divergences. Neither is reachable from the runtime's inputs, so they are
     * left in place rather than unified: changing either would change plugin behaviour for a case
     * the runtime never exercises.
     *
     * <ul>
     *   <li>Slash form: only the plugin accepts it, because only the plugin reads entries the user
     *       typed in {@code build.gradle}. The runtime would turn {@code "com/example/api/"} into
     *       the nonsense prefix {@code "com/example/api/."}.</li>
     *   <li>Blank entries: the runtime drops them, the plugin turns {@code ""} into {@code "."} —
     *       a prefix that matches every class. The consequences are opposite, which is why the
     *       runtime bothers to guard: {@code "."} in the loader shares everything with the parent,
     *       whereas in the plugin it only makes the codegen bridge nothing. Still a footgun, but
     *       fixing it is a behaviour change for the plugin, not a de-duplication.</li>
     * </ul>
     *
     * <p>Both halves of each pair execute production code, so this pins the divergences to
     * exactly the two described: if the runtime stops dropping blanks, or starts translating
     * slashes, the assertion for that half goes red. Previously the runtime half asserted the
     * test's own transcription against a literal and ran no production code at all.
     */
    @Test
    void documentedDivergencesFromTheRuntimeRule() {
        assertEquals(List.of("com.example.api."),
                BridgePolicy.normalizeSharedPackages(List.of("com/example/api/")));
        assertEquals(List.of("com/example/api/."), modClassLoaderRule(List.of("com/example/api/")));

        assertEquals(List.of("."), BridgePolicy.normalizeSharedPackages(List.of("")));
        assertEquals(List.of(), modClassLoaderRule(List.of("")));
    }

    private static byte[] classRefingScala(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Lscala/Option;)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}

package de.lhns.mcdp.gradle.validate;

import de.lhns.mcdp.gradle.bridges.BridgePolicy;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

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

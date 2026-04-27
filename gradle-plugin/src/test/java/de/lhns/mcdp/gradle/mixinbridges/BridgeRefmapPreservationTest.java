package de.lhns.mcdp.gradle.mixinbridges;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the refmap-preservation contract of ADR-0018: the rewriter rewrites mod-private
 * references and leaves target-class references untouched.
 *
 * <p>Sponge Mixin's annotation processor emits a {@code *.refmap.json} from the unrewritten
 * bytecode listing references from the mixin to its <em>target class</em>. If the rewriter
 * disturbed any of those references, Sponge's transformer would not find them in the
 * rewritten class and the mixin would silently fail to apply at runtime. This test builds
 * a synthetic refmap from the constant pool of the unrewritten mixin (everything Sponge's
 * AP would have captured), runs the rewrite, and asserts every target-class reference
 * survives + no mod-private reference remains.</p>
 */
class BridgeRefmapPreservationTest {

    private static final String BRIDGE_PKG = "com.example.refmap_test.bridges";
    private static final String TARGET_OWNER = "net/minecraft/Target";   // platform-prefix, kept
    private static final String MOD_OWNER    = "com/example/MyMod";      // mod-private, bridged

    private final BridgePolicy policy = new BridgePolicy(List.of(), BRIDGE_PKG);

    @Test
    void targetClassReferencesSurviveRewrite() {
        byte[] before = mixinFixture();
        Set<MemberRef> targetRefsBefore = collectRefs(before, TARGET_OWNER);
        assertFalse(targetRefsBefore.isEmpty(),
                "fixture should contain target-class references for the test to be meaningful");

        BridgeMixinScanner scanner = new BridgeMixinScanner(policy);
        MixinScanResult result = scanner.scan(before);
        assertEquals(MixinScanResult.Status.REWRITABLE, result.status());

        MixinRewriter rewriter = new MixinRewriter(policy, BRIDGE_PKG);
        byte[] after = rewriter.rewrite(before, result.targets());

        Set<MemberRef> targetRefsAfter = collectRefs(after, TARGET_OWNER);
        for (MemberRef ref : targetRefsBefore) {
            assertTrue(targetRefsAfter.contains(ref),
                    "rewriter dropped target-class reference: " + ref
                            + " — Sponge's refmap would no longer match.");
        }
    }

    @Test
    void modPrivateReferencesAreGoneAfterRewrite() {
        byte[] before = mixinFixture();
        Set<MemberRef> modBefore = collectRefs(before, MOD_OWNER);
        assertFalse(modBefore.isEmpty(),
                "fixture should reference mod-private types for the test to be meaningful");

        BridgeMixinScanner scanner = new BridgeMixinScanner(policy);
        MixinRewriter rewriter = new MixinRewriter(policy, BRIDGE_PKG);
        byte[] after = rewriter.rewrite(before, scanner.scan(before).targets());

        Set<MemberRef> modAfter = collectRefs(after, MOD_OWNER);
        assertTrue(modAfter.isEmpty(),
                "rewriter left mod-private references behind: " + modAfter);
    }

    /**
     * Build a mixin that exercises every supported v1+v2 opcode against both the target class
     * and a mod-private class — gives the test something realistic to constrain.
     */
    private static byte[] mixinFixture() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/refmap_test/MixinFix",
                null, "java/lang/Object", null);

        // Constructor.
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // handler() exercises:
        //   - INVOKESTATIC + GETSTATIC against target class (Sponge AP would refmap these)
        //   - INVOKESTATIC + GETSTATIC + PUTSTATIC + LDC against mod-private (rewriter rewrites)
        //   - NEW + DUP + INVOKESPECIAL constructor of mod-private (rewriter rewrites)
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "handler", "()V", null, null);
        mv.visitCode();
        // Target-class refs (must survive)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_OWNER, "tickAll", "()V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TARGET_OWNER, "INSTANCE", "L" + TARGET_OWNER + ";");
        mv.visitInsn(Opcodes.POP);
        // Mod-private refs (must be gone)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MOD_OWNER, "doStuff", "()V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, MOD_OWNER, "FLAG", "I");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, MOD_OWNER, "FLAG", "I");
        mv.visitLdcInsn(Type.getObjectType(MOD_OWNER));
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, MOD_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MOD_OWNER, "<init>", "()V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Walk the class's bytecode and collect every member reference whose owner equals
     * {@code wantOwner}. Mirrors what Sponge's AP captures into a refmap from constant-pool
     * entries reachable from method bodies.
     */
    private static Set<MemberRef> collectRefs(byte[] classBytes, String wantOwner) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.SKIP_FRAMES);
        Set<MemberRef> out = new LinkedHashSet<>();
        if (cn.methods == null) return out;
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) continue;
            for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode min && wantOwner.equals(min.owner)) {
                    out.add(new MemberRef(min.owner, min.name, min.desc));
                } else if (insn instanceof FieldInsnNode fin && wantOwner.equals(fin.owner)) {
                    out.add(new MemberRef(fin.owner, fin.name, fin.desc));
                } else if (insn instanceof TypeInsnNode tin && wantOwner.equals(tin.desc)) {
                    out.add(new MemberRef(tin.desc, "", ""));
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type t
                        && t.getSort() == Type.OBJECT && wantOwner.equals(t.getInternalName())) {
                    out.add(new MemberRef(t.getInternalName(), "", ""));
                }
            }
        }
        return out;
    }

    private record MemberRef(String owner, String name, String desc) { }
}

package de.lhns.mcdp.gradle.validate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks one compiled class with ASM and yields the set of every internal-name FQN it
 * references — constant pool entries, descriptors, generic signatures, method/field
 * opcode operands, {@code LDC} {@code Class<?>} constants, {@code INVOKEDYNAMIC} bootstrap
 * args, {@code CHECKCAST}/{@code INSTANCEOF}/{@code NEW}/{@code ANEWARRAY} type operands.
 * <p>
 * Internal names are returned in JVM-internal form ({@code java/lang/String}). Caller
 * decides which references are "offending" — this class is a pure walker.
 */
public final class ClassRefCollector {

    private ClassRefCollector() {}

    public static Set<String> collect(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        return collect(cn);
    }

    public static Set<String> collect(ClassNode cn) {
        Set<String> refs = new LinkedHashSet<>();

        // Class header.
        if (cn.superName != null) refs.add(cn.superName);
        if (cn.interfaces != null) refs.addAll(cn.interfaces);
        if (cn.signature != null) collectFromSignature(cn.signature, refs);
        collectFromAnnotations(cn.visibleAnnotations, refs);
        collectFromAnnotations(cn.invisibleAnnotations, refs);

        if (cn.fields != null) {
            for (FieldNode f : cn.fields) {
                addType(Type.getType(f.desc), refs);
                if (f.signature != null) collectFromSignature(f.signature, refs);
                collectFromAnnotations(f.visibleAnnotations, refs);
                collectFromAnnotations(f.invisibleAnnotations, refs);
            }
        }

        if (cn.methods != null) {
            for (MethodNode m : cn.methods) {
                Type mt = Type.getMethodType(m.desc);
                addType(mt.getReturnType(), refs);
                for (Type t : mt.getArgumentTypes()) addType(t, refs);
                if (m.signature != null) collectFromSignature(m.signature, refs);
                if (m.exceptions != null) refs.addAll(m.exceptions);
                collectFromAnnotations(m.visibleAnnotations, refs);
                collectFromAnnotations(m.invisibleAnnotations, refs);
                if (m.instructions != null) {
                    for (AbstractInsnNode insn : m.instructions) {
                        switch (insn) {
                            case TypeInsnNode t -> refs.add(t.desc);
                            case FieldInsnNode f -> {
                                refs.add(f.owner);
                                addType(Type.getType(f.desc), refs);
                            }
                            case MethodInsnNode mi -> {
                                refs.add(mi.owner);
                                Type t = Type.getMethodType(mi.desc);
                                addType(t.getReturnType(), refs);
                                for (Type a : t.getArgumentTypes()) addType(a, refs);
                            }
                            case LdcInsnNode l -> {
                                if (l.cst instanceof Type lt) addType(lt, refs);
                            }
                            case InvokeDynamicInsnNode idy -> {
                                Type t = Type.getMethodType(idy.desc);
                                addType(t.getReturnType(), refs);
                                for (Type a : t.getArgumentTypes()) addType(a, refs);
                                if (idy.bsmArgs != null) {
                                    for (Object arg : idy.bsmArgs) {
                                        if (arg instanceof Type ta) addType(ta, refs);
                                    }
                                }
                            }
                            case MultiANewArrayInsnNode ma -> addType(Type.getType(ma.desc), refs);
                            default -> {}
                        }
                    }
                }
            }
        }

        return refs;
    }

    private static void addType(Type t, Set<String> refs) {
        switch (t.getSort()) {
            case Type.OBJECT -> refs.add(t.getInternalName());
            case Type.ARRAY -> addType(t.getElementType(), refs);
            default -> {}
        }
    }

    private static void collectFromAnnotations(List<AnnotationNode> anns, Set<String> refs) {
        if (anns == null) return;
        for (AnnotationNode a : anns) {
            if (a.desc != null) addType(Type.getType(a.desc), refs);
        }
    }

    private static void collectFromSignature(String signature, Set<String> refs) {
        new SignatureReader(signature).accept(new SignatureVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override
            public void visitClassType(String name) { refs.add(name); }
        });
    }
}

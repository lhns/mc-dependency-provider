package de.lhns.mcdp.gradle.mixinbridges;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers ADR-0021's annotation-seed discovery path. The scanner is a single ASM pass per class;
 * it never resolves transitive references, so these tests can target arbitrary annotation FQNs
 * without needing the real Sponge / NeoForge classes on the test classpath.
 */
class AnnotationSeedScannerTest {

    private static final String MIXIN = "org.spongepowered.asm.mixin.Mixin";
    private static final String EBS = "net.neoforged.bus.api.EventBusSubscriber";

    @Test
    void matchesByConfiguredAnnotation() {
        byte[] bytes = classWithAnnotations("com/example/MyMixin", List.of(MIXIN));
        String fqn = new AnnotationSeedScanner(List.of(MIXIN)).matchClass(bytes);
        assertEquals("com.example.MyMixin", fqn);
    }

    @Test
    void noAnnotationMeansNoMatch() {
        byte[] bytes = classWithAnnotations("com/example/Plain", List.of());
        assertNull(new AnnotationSeedScanner(List.of(MIXIN)).matchClass(bytes));
    }

    @Test
    void unconfiguredAnnotationDoesNotMatch() {
        byte[] bytes = classWithAnnotations("com/example/Other",
                List.of("com.example.Unrelated"));
        assertNull(new AnnotationSeedScanner(List.of(MIXIN, EBS)).matchClass(bytes));
    }

    @Test
    void dualAnnotatedMatchesOnce() {
        byte[] bytes = classWithAnnotations("com/example/Both", List.of(MIXIN, EBS));
        String fqn = new AnnotationSeedScanner(List.of(MIXIN, EBS)).matchClass(bytes);
        assertEquals("com.example.Both", fqn);
    }

    @Test
    void anyOfConfiguredMatches() {
        byte[] bytes = classWithAnnotations("com/example/SubOnly", List.of(EBS));
        String fqn = new AnnotationSeedScanner(List.of(MIXIN, EBS)).matchClass(bytes);
        assertEquals("com.example.SubOnly", fqn);
    }

    @Test
    void emptyConfigYieldsEmptySet() throws IOException {
        Set<String> result = new AnnotationSeedScanner(List.of()).scan(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void scanWalksDirectoryAndFindsMatches(@TempDir Path tmp) throws IOException {
        // Drop two .class files: one annotated, one plain. Scanner should return only the
        // annotated FQN.
        Path mixinPath = tmp.resolve("com/example/MyMixin.class");
        Files.createDirectories(mixinPath.getParent());
        Files.write(mixinPath, classWithAnnotations("com/example/MyMixin", List.of(MIXIN)));

        Path plainPath = tmp.resolve("com/example/Plain.class");
        Files.write(plainPath, classWithAnnotations("com/example/Plain", List.of()));

        Path subPath = tmp.resolve("com/example/Subscriber.class");
        Files.write(subPath, classWithAnnotations("com/example/Subscriber", List.of(EBS)));

        Set<String> matched = new AnnotationSeedScanner(List.of(MIXIN, EBS)).scan(List.of(tmp));
        assertEquals(Set.of("com.example.MyMixin", "com.example.Subscriber"), matched);
    }

    @Test
    void scanIgnoresNonClassFiles(@TempDir Path tmp) throws IOException {
        Path classPath = tmp.resolve("com/example/MyMixin.class");
        Files.createDirectories(classPath.getParent());
        Files.write(classPath, classWithAnnotations("com/example/MyMixin", List.of(MIXIN)));
        Files.writeString(tmp.resolve("README.md"), "# nope");
        Files.writeString(tmp.resolve("com/example/notes.txt"), "ignored");

        Set<String> matched = new AnnotationSeedScanner(List.of(MIXIN)).scan(List.of(tmp));
        assertEquals(Set.of("com.example.MyMixin"), matched);
    }

    @Test
    void scanSkipsMissingDirectoryGracefully() throws IOException {
        Set<String> matched = new AnnotationSeedScanner(List.of(MIXIN))
                .scan(List.of(Path.of("does-not-exist-anywhere")));
        assertTrue(matched.isEmpty());
    }

    @Test
    void invisibleAndVisibleAnnotationsBothMatch() {
        // Sponge's @Mixin has RUNTIME retention (visible). NeoForge's @EventBusSubscriber also
        // has RUNTIME retention. But guard against future CLASS-retention variants by walking
        // both visible and invisible annotation tables.
        byte[] bytes = classWithMixedAnnotations("com/example/Cls", MIXIN, true);
        assertEquals("com.example.Cls",
                new AnnotationSeedScanner(List.of(MIXIN)).matchClass(bytes));
        byte[] invisible = classWithMixedAnnotations("com/example/Cls", MIXIN, false);
        assertEquals("com.example.Cls",
                new AnnotationSeedScanner(List.of(MIXIN)).matchClass(invisible));
    }

    /**
     * Builds a class with a public no-arg ctor and the given annotation FQNs as visible
     * class-level annotations.
     */
    private static byte[] classWithAnnotations(String internalName, List<String> annotationFqns) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        for (String fqn : annotationFqns) {
            String desc = "L" + fqn.replace('.', '/') + ";";
            AnnotationVisitor av = cw.visitAnnotation(desc, true);
            if (av != null) av.visitEnd();
        }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classWithMixedAnnotations(String internalName, String fqn, boolean visible) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        AnnotationVisitor av = cw.visitAnnotation("L" + fqn.replace('.', '/') + ";", visible);
        if (av != null) av.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}

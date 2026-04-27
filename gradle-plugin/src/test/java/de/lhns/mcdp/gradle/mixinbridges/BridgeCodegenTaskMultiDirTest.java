package de.lhns.mcdp.gradle.mixinbridges;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the multi-output-dir scan: when {@code .java} mixins are joint-compiled
 * by scalac/kotlinc, their bytecode lands under {@code compileScala}'s or {@code compileKotlin}'s
 * output, not {@code compileJava}'s. The codegen task must search every {@code SourceSet} output
 * dir to find the declared mixin class.
 */
class BridgeCodegenTaskMultiDirTest {

    private static final String MIXIN_FQN = "com.example.Mixin";
    private static final String TARGET_INTERNAL = "com/example/Target";

    @Test
    void findsMixinInSecondDirWhenFirstIsEmpty(@TempDir Path tmp) throws IOException {
        // Dir A: empty (simulates compileJava output that scalac wiped/never populated).
        Path dirA = Files.createDirectories(tmp.resolve("classes/java/main"));
        // Dir B: contains the mixin bytecode, as if scalac wrote it during joint compilation.
        Path dirB = Files.createDirectories(tmp.resolve("classes/scala/main"));
        Path mixinClass = dirB.resolve("com/example/Mixin.class");
        Files.createDirectories(mixinClass.getParent());
        Files.write(mixinClass, mixinCallingTargetStatic());

        Path mixinsJson = tmp.resolve("resources/test.mixins.json");
        Files.createDirectories(mixinsJson.getParent());
        Files.writeString(mixinsJson,
                "{\"package\":\"com.example\",\"mixins\":[\"Mixin\"]}",
                StandardCharsets.UTF_8);

        Project project = ProjectBuilder.builder().withProjectDir(tmp.toFile()).build();
        BridgeCodegenTask task = project.getTasks().register(
                "generateMcdpMixinBridges", BridgeCodegenTask.class).get();
        task.getCompiledClassesDirs().from(dirA.toFile(), dirB.toFile());
        task.getMixinConfigFiles().from(mixinsJson.toFile());
        task.getBridgePackage().set("com.example.bridges");
        task.getSharedPackages().set(java.util.List.of());
        Path outClasses = tmp.resolve("out/classes");
        Path outManifest = tmp.resolve("out/resources");
        Path report = tmp.resolve("out/report.txt");
        task.getOutputClassesDir().set(outClasses.toFile());
        task.getManifestOutputDir().set(outManifest.toFile());
        task.getReportFile().set(report.toFile());

        task.run();

        // Rewritten class file landed in the output.
        Path rewritten = outClasses.resolve("com/example/Mixin.class");
        assertTrue(Files.isRegularFile(rewritten), "rewritten Mixin.class missing at " + rewritten);

        // Unified TOML manifest emitted (ADR-0019). Single file per mod with [[bridge]] entries
        // — replaces per-mixin .txt + index.txt scheme.
        Path manifestToml = outManifest.resolve("META-INF/mcdp-mixin-bridges.toml");
        assertTrue(Files.isRegularFile(manifestToml), "manifest TOML missing at " + manifestToml);
        String tomlText = Files.readString(manifestToml, StandardCharsets.UTF_8);
        assertTrue(tomlText.contains("[[bridge]]"), tomlText);
        assertTrue(tomlText.contains("mixin = \"" + MIXIN_FQN + "\""), tomlText);
        // Parse via MiniToml — same parser the runtime uses.
        var parsed = de.lhns.mcdp.deps.MiniToml.parse(tomlText);
        @SuppressWarnings("unchecked")
        var entries = (java.util.List<java.util.Map<String, String>>) parsed.get("bridge");
        assertEquals(1, entries.size(), "expected exactly one [[bridge]] entry");
        assertEquals(MIXIN_FQN, entries.get(0).get("mixin"));
        assertEquals("LOGIC_Target", entries.get(0).get("field"));

        // Report lists the mixin and no "not found" warning was raised.
        String reportText = Files.readString(report);
        assertTrue(reportText.contains("rewritten mixins (1)"), reportText);
        assertTrue(reportText.contains(MIXIN_FQN), reportText);
        assertFalse(reportText.contains("not found in any compiled-classes dir"),
                "got 'not found' warning even though class was in second dir:\n" + reportText);
    }

    @Test
    void warnsListingAllDirsWhenMixinClassIsAbsent(@TempDir Path tmp) throws IOException {
        Path dirA = Files.createDirectories(tmp.resolve("classes/java/main"));
        Path dirB = Files.createDirectories(tmp.resolve("classes/scala/main"));

        Path mixinsJson = tmp.resolve("resources/test.mixins.json");
        Files.createDirectories(mixinsJson.getParent());
        Files.writeString(mixinsJson,
                "{\"package\":\"com.example\",\"mixins\":[\"MissingMixin\"]}",
                StandardCharsets.UTF_8);

        Project project = ProjectBuilder.builder().withProjectDir(tmp.toFile()).build();
        BridgeCodegenTask task = project.getTasks().register(
                "generateMcdpMixinBridges", BridgeCodegenTask.class).get();
        task.getCompiledClassesDirs().from(dirA.toFile(), dirB.toFile());
        task.getMixinConfigFiles().from(mixinsJson.toFile());
        task.getBridgePackage().set("com.example.bridges");
        task.getSharedPackages().set(java.util.List.of());
        task.getOutputClassesDir().set(tmp.resolve("out/classes").toFile());
        task.getManifestOutputDir().set(tmp.resolve("out/resources").toFile());
        Path report = tmp.resolve("out/report.txt");
        task.getReportFile().set(report.toFile());

        task.run();

        String reportText = Files.readString(report);
        assertTrue(reportText.contains("not found in any compiled-classes dir"),
                "expected the not-found warning text:\n" + reportText);
        // Both searched dirs appear in the message.
        assertTrue(reportText.contains(dirA.toString().replace('\\', '/'))
                        || reportText.contains(dirA.toString()),
                "warning should list dirA (" + dirA + "):\n" + reportText);
        assertTrue(reportText.contains(dirB.toString().replace('\\', '/'))
                        || reportText.contains(dirB.toString()),
                "warning should list dirB (" + dirB + "):\n" + reportText);
        assertEquals(0, java.util.stream.Stream.of(reportText.split("\n"))
                        .filter(l -> l.contains("rewritten mixins (1)")).count(),
                "no mixin should have been rewritten");
    }

    /**
     * Bytecode for {@code com/example/Mixin} containing one static method that calls
     * {@code com/example/Target.doubleIt(I)I}. The scanner classifies this as REWRITABLE
     * (Target is mod-private under the empty {@code sharedPackages} policy), giving us a positive
     * "made it through the rewrite pipeline" signal — proving the file was located.
     */
    private static byte[] mixinCallingTargetStatic() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/Mixin", null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "handler", "(I)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_INTERNAL, "doubleIt", "(I)I", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}

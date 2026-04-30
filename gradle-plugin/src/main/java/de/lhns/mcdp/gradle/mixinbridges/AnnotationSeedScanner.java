package de.lhns.mcdp.gradle.mixinbridges;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Discovers candidate classes for the bridge codegen by walking compiled-class output dirs and
 * matching each class's <em>class-level</em> annotations against a configured FQN list.
 *
 * <p>Generalizes ADR-0018's mixin-config-only seed: the codegen is no longer Sponge-Mixin-specific,
 * and seeds equally on {@code @Mixin}, {@code @EventBusSubscriber}, or any other annotation a mod
 * author lists. See ADR-0021.</p>
 *
 * <p>Only the class-level annotation table is read. Method/field annotations are skipped — the
 * codegen rewrites whole classes, not individual members, so per-member annotations don't seed.</p>
 */
public final class AnnotationSeedScanner {

    private final Set<String> targetAnnotationDescriptors;

    /**
     * @param annotationFqns dotted-form annotation FQNs to seed on (e.g.
     *                       {@code "org.spongepowered.asm.mixin.Mixin"}).
     */
    public AnnotationSeedScanner(List<String> annotationFqns) {
        Set<String> descs = new LinkedHashSet<>();
        for (String fqn : annotationFqns) {
            descs.add("L" + BridgePolicy.toInternal(Objects.requireNonNull(fqn)) + ";");
        }
        this.targetAnnotationDescriptors = Set.copyOf(descs);
    }

    /**
     * Scan every {@code .class} file under each given directory and return the dotted FQN set of
     * classes whose class-level annotation table includes at least one configured annotation.
     */
    public Set<String> scan(List<Path> classDirs) throws IOException {
        if (targetAnnotationDescriptors.isEmpty()) return Set.of();
        Set<String> matched = new LinkedHashSet<>();
        for (Path dir : classDirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(p)) continue;
                    if (!p.getFileName().toString().endsWith(".class")) continue;
                    byte[] bytes = Files.readAllBytes(p);
                    String fqn = matchClass(bytes);
                    if (fqn != null) matched.add(fqn);
                }
            }
        }
        return matched;
    }

    /**
     * @return the dotted FQN if the class carries any of the configured annotations, else
     *         {@code null}.
     */
    String matchClass(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        Matcher m = new Matcher(targetAnnotationDescriptors);
        // SKIP_CODE + SKIP_DEBUG + SKIP_FRAMES — we only need the class name and class-level
        // annotation table; method bodies are irrelevant for seeding.
        cr.accept(m, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (m.matched) {
            return BridgePolicy.toDotted(m.internalName);
        }
        return null;
    }

    private static final class Matcher extends ClassVisitor {
        private final Set<String> targets;
        String internalName;
        boolean matched;

        Matcher(Set<String> targets) {
            super(Opcodes.ASM9);
            this.targets = targets;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.internalName = name;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (targets.contains(descriptor)) matched = true;
            return null;
        }
    }
}

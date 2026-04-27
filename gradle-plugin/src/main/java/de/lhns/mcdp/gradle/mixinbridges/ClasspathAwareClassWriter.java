package de.lhns.mcdp.gradle.mixinbridges;

import org.objectweb.asm.ClassWriter;

/**
 * {@link ClassWriter} subclass that resolves {@link #getClassLoader()} to a caller-supplied
 * loader. ASM's default {@code getCommonSuperClass} does {@code Class.forName(t, false, getClassLoader())};
 * the default {@code getClassLoader()} returns whichever loader loaded {@code ClassWriter.class},
 * which for us is the gradle-plugin classloader and cannot see Minecraft types. Pointing it at the
 * consumer's {@code compileClasspath} fixes stackmap-frame computation for mixins that push two
 * MC reference types across a branch join.
 */
final class ClasspathAwareClassWriter extends ClassWriter {

    private final ClassLoader cp;

    ClasspathAwareClassWriter(int flags, ClassLoader cp) {
        super(flags);
        this.cp = cp;
    }

    @Override
    protected ClassLoader getClassLoader() {
        return cp;
    }
}

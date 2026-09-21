package de.lhns.mcdp.gradle.bridges;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bundled ASM has to be able to <em>read</em> class files as new as the newest JDK any band
 * compiles against, not just the one the plugin itself targets.
 *
 * <p>Regression test for the NeoForge 26.x cell: those mods compile on a JDK 25 toolchain, and
 * {@code generateMcdpBridges} died with {@code Unsupported class file major version 69} because
 * the pinned ASM predated Java 25. The scanner reads every compiled class in the source set, so
 * an ASM that cannot parse the toolchain's output fails the whole build.
 */
class ClassFileVersionSupportTest {

    /** Java 25. Written as a literal so a future ASM constant rename cannot silently weaken this. */
    private static final int MAJOR_JAVA_25 = 69;

    @Test
    void readsJava25ClassFiles() {
        assertEquals(MAJOR_JAVA_25, majorVersionOfReadableClass(MAJOR_JAVA_25));
    }

    /** One past the newest supported release — the guard mcdp relies on to fail loudly, not silently. */
    @Test
    void asmKnowsWhereItsCeilingIs() {
        // Just documents that ClassReader validates rather than mis-parsing: a version it does
        // not know throws, which is how the 26.x failure surfaced as a clear message.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> majorVersionOfReadableClass(200));
    }

    private static int majorVersionOfReadableClass(int major) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(major, Opcodes.ACC_PUBLIC, "com/example/Probe", null, "java/lang/Object", null);
        cw.visitEnd();
        // ClassReader is what BridgeScanner and ClassRefCollector both go through.
        return new ClassReader(cw.toByteArray()).readShort(6);
    }
}

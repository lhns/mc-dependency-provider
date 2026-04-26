package de.lhns.mcdp.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Test utility: assemble a jar file containing a supplied set of {@code .class} entries.
 * Uses raw bytecode emitted without any bytecode-library — we pre-compile test classes in-memory
 * with {@link javax.tools.JavaCompiler} and then package them.
 */
final class SyntheticJar {

    private SyntheticJar() {}

    static Path writeJar(Path target, java.util.Map<String, byte[]> classFiles) throws IOException {
        Files.createDirectories(target.getParent());
        try (var out = new JarOutputStream(Files.newOutputStream(target))) {
            for (var e : classFiles.entrySet()) {
                JarEntry entry = new JarEntry(e.getKey());
                out.putNextEntry(entry);
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return target;
    }

    /** Emit a minimal empty class in the given internal name (e.g. "cats/kernel/instances/byte/Foo"). */
    static byte[] emptyClass(String internalName) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
        try {
            // CAFEBABE
            dos.writeInt(0xCAFEBABE);
            // minor + major = Java 21 (65.0)
            dos.writeShort(0);
            dos.writeShort(65);

            // constant_pool_count = 8
            // #1 = Utf8  className
            // #2 = Class #1
            // #3 = Utf8  "java/lang/Object"
            // #4 = Class #3
            // #5 = Utf8  "<init>"
            // #6 = Utf8  "()V"
            // #7 = Utf8  "Code"
            // #8 = NameAndType #5:#6
            // #9 = Methodref #4.#8
            // We'll emit these in order.
            dos.writeShort(10); // constant_pool_count = 10 (indices 1..9)

            // #1 Utf8 className
            dos.writeByte(1);
            dos.writeUTF(internalName);
            // #2 Class #1
            dos.writeByte(7);
            dos.writeShort(1);
            // #3 Utf8 java/lang/Object
            dos.writeByte(1);
            dos.writeUTF("java/lang/Object");
            // #4 Class #3
            dos.writeByte(7);
            dos.writeShort(3);
            // #5 Utf8 <init>
            dos.writeByte(1);
            dos.writeUTF("<init>");
            // #6 Utf8 ()V
            dos.writeByte(1);
            dos.writeUTF("()V");
            // #7 Utf8 Code
            dos.writeByte(1);
            dos.writeUTF("Code");
            // #8 NameAndType #5:#6
            dos.writeByte(12);
            dos.writeShort(5);
            dos.writeShort(6);
            // #9 Methodref #4.#8
            dos.writeByte(10);
            dos.writeShort(4);
            dos.writeShort(8);

            // access_flags = ACC_PUBLIC | ACC_SUPER
            dos.writeShort(0x0021);
            // this_class = #2
            dos.writeShort(2);
            // super_class = #4
            dos.writeShort(4);
            // interfaces_count, fields_count = 0
            dos.writeShort(0);
            dos.writeShort(0);
            // methods_count = 1 — a default no-arg constructor
            dos.writeShort(1);
            // method: ACC_PUBLIC, name=#5, descriptor=#6, attributes_count=1
            dos.writeShort(0x0001);
            dos.writeShort(5);
            dos.writeShort(6);
            dos.writeShort(1);
            // Code attribute: name=#7, length, max_stack=1, max_locals=1, code_length=5,
            //   code = aload_0 (0x2A), invokespecial #9 (0xB7 00 09), return (0xB1)
            dos.writeShort(7);
            dos.writeInt(5 + 8 + 4); // Code attribute bytes — see below
            dos.writeShort(1); // max_stack
            dos.writeShort(1); // max_locals
            dos.writeInt(5);   // code_length
            dos.writeByte(0x2A);
            dos.writeByte(0xB7);
            dos.writeShort(9);
            dos.writeByte(0xB1);
            dos.writeShort(0); // exception_table_length
            dos.writeShort(0); // attributes_count (of the Code attribute)
            // attributes_count (of the class) = 0
            dos.writeShort(0);

            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

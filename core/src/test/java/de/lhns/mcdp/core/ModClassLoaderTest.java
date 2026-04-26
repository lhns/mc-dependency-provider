package de.lhns.mcdp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModClassLoaderTest {

    @Test
    void loadsClassInJavaKeywordPackage(@TempDir Path tmp) throws Exception {
        // This is the whole point of ADR-0002 — a URLClassLoader can load classes whose package
        // names contain Java keywords ('byte', 'int', etc.), because the resulting class ends up
        // in the loader's unnamed module which doesn't undergo ModuleDescriptor package validation.
        String internalName = "cats/kernel/instances/byte/Foo";
        byte[] bytes = SyntheticJar.emptyClass(internalName);
        Path jar = tmp.resolve("synthetic.jar");
        SyntheticJar.writeJar(jar, Map.of(internalName + ".class", bytes));

        try (ModClassLoader cl = new ModClassLoader(
                "test-mod",
                new java.net.URL[]{jar.toUri().toURL()},
                getClass().getClassLoader(),
                List.of())) {

            Class<?> loaded = Class.forName("cats.kernel.instances.byte.Foo", true, cl);
            assertNotNull(loaded);
            assertEquals("cats.kernel.instances.byte.Foo", loaded.getName());
            assertSame(cl, loaded.getClassLoader());
            assertFalse(loaded.getModule().isNamed(), "class should be in an unnamed module");
        }
    }

    @Test
    void parentFirstForPlatformPrefix() throws Exception {
        try (ModClassLoader cl = new ModClassLoader(
                "test-mod",
                new java.net.URL[]{},
                getClass().getClassLoader(),
                List.of())) {
            Class<?> string = cl.loadClass("java.lang.String");
            assertSame(String.class, string);
        }
    }

    @Test
    void sharedPackagesRouteToParent(@TempDir Path tmp) throws Exception {
        String internal = "com/example/api/Shared";
        Path parentJar = tmp.resolve("shared.jar");
        SyntheticJar.writeJar(parentJar, Map.of(internal + ".class", SyntheticJar.emptyClass(internal)));

        Path modJar = tmp.resolve("mod.jar");
        SyntheticJar.writeJar(modJar, Map.of(internal + ".class", SyntheticJar.emptyClass(internal)));

        try (URLClassLoader parent = new URLClassLoader(
                new java.net.URL[]{parentJar.toUri().toURL()},
                getClass().getClassLoader());
             ModClassLoader cl = new ModClassLoader(
                     "test-mod",
                     new java.net.URL[]{modJar.toUri().toURL()},
                     parent,
                     List.of("com.example.api."))) {

            Class<?> loaded = cl.loadClass("com.example.api.Shared");
            assertSame(parent, loaded.getClassLoader(),
                    "class under a shared_packages prefix must come from the parent, not the mod loader");
        }
    }
}

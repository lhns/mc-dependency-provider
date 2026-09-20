package de.lhns.mcdp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /**
     * Resources must follow the same child-first policy as classes: a mod that ships its own
     * {@code reference.conf} has to see its own copy, not the game layer's.
     */
    @Test
    void childFirstForResources(@TempDir Path tmp) throws Exception {
        Path parentJar = tmp.resolve("parent-res.jar");
        SyntheticJar.writeJar(parentJar, Map.of(
                "reference.conf", "parent".getBytes(StandardCharsets.UTF_8)));
        Path modJar = tmp.resolve("mod-res.jar");
        SyntheticJar.writeJar(modJar, Map.of(
                "reference.conf", "child".getBytes(StandardCharsets.UTF_8),
                "META-INF/services/com.example.Svc", "com.example.ModImpl".getBytes(StandardCharsets.UTF_8)));

        try (URLClassLoader parent = new URLClassLoader(
                new java.net.URL[]{parentJar.toUri().toURL()}, getClass().getClassLoader());
             ModClassLoader cl = new ModClassLoader(
                     "test-mod",
                     new java.net.URL[]{modJar.toUri().toURL()},
                     parent,
                     List.of())) {

            assertEquals("child", read(cl.getResource("reference.conf")));
            try (var in = cl.getResourceAsStream("reference.conf")) {
                assertEquals("child", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }

            // getResources: the mod's own entry comes first, the parent's is still reachable.
            List<String> all = new ArrayList<>();
            for (var e = cl.getResources("reference.conf"); e.hasMoreElements(); ) {
                all.add(read(e.nextElement()));
            }
            assertEquals("child", all.get(0), "the mod's own entry must come first");
            assertTrue(all.contains("parent"), "the parent's entry must still be reachable");

            // The ServiceLoader case: only the mod has this file, and it must be visible.
            assertEquals("com.example.ModImpl", read(cl.getResource("META-INF/services/com.example.Svc")));
        }
    }

    /** A resource under a shared package belongs to the parent, mirroring the class policy. */
    @Test
    void parentFirstForSharedPackageResources(@TempDir Path tmp) throws Exception {
        String res = "com/example/api/shared.properties";
        Path parentJar = tmp.resolve("parent-shared.jar");
        SyntheticJar.writeJar(parentJar, Map.of(res, "parent".getBytes(StandardCharsets.UTF_8)));
        Path modJar = tmp.resolve("mod-shared.jar");
        SyntheticJar.writeJar(modJar, Map.of(res, "child".getBytes(StandardCharsets.UTF_8)));

        try (URLClassLoader parent = new URLClassLoader(
                new java.net.URL[]{parentJar.toUri().toURL()}, getClass().getClassLoader());
             ModClassLoader cl = new ModClassLoader(
                     "test-mod",
                     new java.net.URL[]{modJar.toUri().toURL()},
                     parent,
                     List.of("com.example.api."))) {

            assertEquals("parent", read(cl.getResource(res)));
            List<String> all = new ArrayList<>();
            for (var e = cl.getResources(res); e.hasMoreElements(); ) {
                all.add(read(e.nextElement()));
            }
            assertEquals("parent", all.get(0), "the shared entry must come from the parent first");
            assertTrue(all.contains("child"));
        }
    }

    /**
     * Shared-package prefixes are matched with {@code startsWith}, so an entry written without a
     * trailing dot must not capture the neighbouring package {@code com.example.apix} — that
     * would hand the parent a class the mod is supposed to own.
     */
    @Test
    void undottedSharedPackageDoesNotOverMatchNeighbourPackage(@TempDir Path tmp) throws Exception {
        String shared = "com/example/api/Shared";
        String neighbour = "com/example/apix/Foo";
        Path parentJar = tmp.resolve("parent-overmatch.jar");
        SyntheticJar.writeJar(parentJar, Map.of(
                shared + ".class", SyntheticJar.emptyClass(shared),
                neighbour + ".class", SyntheticJar.emptyClass(neighbour)));
        Path modJar = tmp.resolve("mod-overmatch.jar");
        SyntheticJar.writeJar(modJar, Map.of(
                neighbour + ".class", SyntheticJar.emptyClass(neighbour)));

        try (URLClassLoader parent = new URLClassLoader(
                new java.net.URL[]{parentJar.toUri().toURL()}, getClass().getClassLoader());
             ModClassLoader cl = new ModClassLoader(
                     "test-mod",
                     new java.net.URL[]{modJar.toUri().toURL()},
                     parent,
                     List.of("com.example.api"))) { // no trailing dot — normalized by the loader

            assertEquals(List.of("com.example.api."), cl.sharedPackages());
            assertSame(parent, cl.loadClass("com.example.api.Shared").getClassLoader(),
                    "the genuinely shared package still resolves parent-first");
            assertSame(cl, cl.loadClass("com.example.apix.Foo").getClassLoader(),
                    "a package that merely shares a prefix stays mod-private");
        }
    }

    /** Reads a resource without letting the JVM's JarFile cache pin the temp jar on Windows. */
    private static String read(java.net.URL url) throws Exception {
        assertNotNull(url);
        var connection = url.openConnection();
        connection.setUseCaches(false);
        try (var in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

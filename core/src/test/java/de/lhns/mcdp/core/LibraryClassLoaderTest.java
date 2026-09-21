package de.lhns.mcdp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LibraryClassLoaderTest {

    /**
     * The reason this loader exists: a library jar we resolved must win over whatever copy the
     * game layer (Knot, the NeoForge module layer) happens to carry — for classes and for the
     * {@code reference.conf} / {@code library.properties} / {@code META-INF/services} entries that
     * drive those libraries' own initialisation.
     */
    @Test
    void childFirstForLibraryResources(@TempDir Path tmp) throws Exception {
        Path parentJar = tmp.resolve("game-layer.jar");
        SyntheticJar.writeJar(parentJar, Map.of(
                "reference.conf", bytes("parent"),
                "only-in-parent.conf", bytes("parent")));
        Path libJar = tmp.resolve("library.jar");
        SyntheticJar.writeJar(libJar, Map.of(
                "reference.conf", bytes("child"),
                "META-INF/services/cats.effect.IORuntimeConfigDefaults", bytes("com.example.LibImpl")));

        try (URLClassLoader parent = new URLClassLoader(
                new URL[]{parentJar.toUri().toURL()}, getClass().getClassLoader());
             LibraryClassLoader cl = libraries(parent, libJar)) {

            assertEquals("child", read(cl.getResource("reference.conf")),
                    "the resolved library's own copy must win over the game layer's");
            try (var in = cl.getResourceAsStream("reference.conf")) {
                assertEquals("child", new String(in.readAllBytes(), StandardCharsets.UTF_8),
                        "getResourceAsStream must agree with getResource");
            }

            // getResources: our entry first, the parent's still reachable behind it.
            List<String> all = readAll(cl, "reference.conf");
            assertEquals("child", all.get(0), "the library's own entry must come first");
            assertTrue(all.contains("parent"), "the game layer's entry must still be reachable");

            // ServiceLoader against this loader has to see the library's providers, not the game's.
            assertEquals("com.example.LibImpl",
                    read(cl.getResource("META-INF/services/cats.effect.IORuntimeConfigDefaults")));

            // Child-first still falls back: a resource only the parent has stays visible.
            assertEquals("parent", read(cl.getResource("only-in-parent.conf")));
        }
    }

    /**
     * The other half of the policy: names that map onto a {@link ModClassLoader#PLATFORM_PREFIXES}
     * package belong to the platform, so a library shading its own copy of, say, an slf4j resource
     * must not shadow the game's. The {@code com/example} case in the same loader is the control —
     * it proves the parent-first answer comes from the prefix check and not from plain
     * {@code URLClassLoader} delegation.
     */
    @Test
    void parentFirstForPlatformPrefixResources(@TempDir Path tmp) throws Exception {
        Path parentJar = tmp.resolve("platform.jar");
        SyntheticJar.writeJar(parentJar, Map.of(
                "org/slf4j/binding.properties", bytes("parent"),
                "com/example/lib/lib.properties", bytes("parent")));
        Path libJar = tmp.resolve("shading.jar");
        SyntheticJar.writeJar(libJar, Map.of(
                "org/slf4j/binding.properties", bytes("child"),
                "org/slf4j/unshipped.properties", bytes("child"),
                "com/example/lib/lib.properties", bytes("child")));

        try (URLClassLoader parent = new URLClassLoader(
                new URL[]{parentJar.toUri().toURL()}, getClass().getClassLoader());
             LibraryClassLoader cl = libraries(parent, libJar)) {

            assertEquals("parent", read(cl.getResource("org/slf4j/binding.properties")),
                    "a platform-prefixed resource must come from the parent");
            assertEquals("parent", readAll(cl, "org/slf4j/binding.properties").get(0),
                    "parent-first also decides getResources ordering");

            // Parent-first is still only a preference: the parent not having it is not an error.
            assertEquals("child", read(cl.getResource("org/slf4j/unshipped.properties")),
                    "parent-first must fall back to our own URLs when the parent has no copy");

            // Control: same loaders, non-platform name — this one is ours.
            assertEquals("child", read(cl.getResource("com/example/lib/lib.properties")),
                    "only platform prefixes are parent-first; everything else stays child-first");
        }
    }

    /**
     * {@code getResources} merges rather than picks, and it must not collapse the multiple library
     * jars this loader coalesces — a half-enumerated {@code META-INF/services} is how a
     * ServiceLoader silently loses providers.
     */
    @Test
    void getResourcesEnumeratesEveryCopyChildSideFirst(@TempDir Path tmp) throws Exception {
        Path parentJar = tmp.resolve("merge-parent.jar");
        SyntheticJar.writeJar(parentJar, Map.of("reference.conf", bytes("parent")));
        Path libA = tmp.resolve("merge-a.jar");
        SyntheticJar.writeJar(libA, Map.of("reference.conf", bytes("a")));
        Path libB = tmp.resolve("merge-b.jar");
        SyntheticJar.writeJar(libB, Map.of("reference.conf", bytes("b")));

        try (URLClassLoader parent = new URLClassLoader(
                new URL[]{parentJar.toUri().toURL()}, getClass().getClassLoader());
             LibraryClassLoader cl = libraries(parent, libA, libB)) {

            List<String> all = readAll(cl, "reference.conf");
            assertEquals(List.of("a", "b"), all.subList(0, 2),
                    "both library jars, in URL order, ahead of the parent");
            assertTrue(all.contains("parent"), "and the parent's copy is still enumerated");
        }
    }

    /** A resource nobody has is absent, not an exception — {@code ServiceLoader} probes constantly. */
    @Test
    void missingResourceIsAbsentRatherThanAnError(@TempDir Path tmp) throws Exception {
        Path libJar = tmp.resolve("empty-ish.jar");
        SyntheticJar.writeJar(libJar, Map.of("present.conf", bytes("child")));

        try (LibraryClassLoader cl = libraries(getClass().getClassLoader(), libJar)) {
            assertNull(cl.getResource("de/lhns/mcdp/core/missing.conf"),
                    "the parent-first branch has to end in null too, not in the parent's answer");
            assertNull(cl.getResource("com/example/nowhere/missing.conf"));
            assertNull(cl.getResourceAsStream("com/example/nowhere/missing.conf"),
                    "getResourceAsStream must return null, not dereference a null URL");
            assertFalse(cl.getResources("com/example/nowhere/missing.conf").hasMoreElements(),
                    "an empty enumeration, from both sides being empty");
        }
    }

    /**
     * Windows only, because that is where it bites: the JVM-wide {@code JarFile} cache behind a
     * plain {@code URL.openStream()} keeps a handle on the jar that survives {@code close()} and
     * pins the file, so the launcher can never clean or replace a resolved library.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void getResourceAsStreamDoesNotPinTheLibraryJar(@TempDir Path tmp) throws Exception {
        Path libJar = tmp.resolve("pinned.jar");
        SyntheticJar.writeJar(libJar, Map.of("reference.conf", bytes("child")));

        try (LibraryClassLoader cl = libraries(getClass().getClassLoader(), libJar)) {
            try (var in = cl.getResourceAsStream("reference.conf")) {
                assertEquals("child", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        assertTrue(Files.deleteIfExists(libJar),
                "the jar must be deletable once the loader is closed — no cached JarFile handle");
    }

    /**
     * The resource policy is documented as mirroring the class policy, so pin the class policy it
     * mirrors: our URLs win, except under a platform prefix where the game's {@code Class} must be
     * the one everybody shares.
     */
    @Test
    void classesAreChildFirstExceptUnderPlatformPrefixes(@TempDir Path tmp) throws Exception {
        String shared = "org/slf4j/FakeLogger";
        String own = "com/example/lib/Widget";
        Path parentJar = tmp.resolve("classes-parent.jar");
        SyntheticJar.writeJar(parentJar, Map.of(
                shared + ".class", SyntheticJar.emptyClass(shared),
                own + ".class", SyntheticJar.emptyClass(own)));
        Path libJar = tmp.resolve("classes-lib.jar");
        SyntheticJar.writeJar(libJar, Map.of(
                shared + ".class", SyntheticJar.emptyClass(shared),
                own + ".class", SyntheticJar.emptyClass(own)));

        try (URLClassLoader parent = new URLClassLoader(
                new URL[]{parentJar.toUri().toURL()}, getClass().getClassLoader());
             LibraryClassLoader cl = libraries(parent, libJar)) {

            assertSame(cl, cl.loadClass(own.replace('/', '.')).getClassLoader(),
                    "a library class must resolve out of our own URLs");
            assertSame(parent, cl.loadClass(shared.replace('/', '.')).getClassLoader(),
                    "a platform-prefixed class must be the parent's, so it is the same Class");
        }
    }

    private static LibraryClassLoader libraries(ClassLoader parent, Path... jars) throws Exception {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toUri().toURL();
        return new LibraryClassLoader("test-libs", urls, parent);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> readAll(ClassLoader cl, String name) throws Exception {
        List<String> all = new ArrayList<>();
        for (var e = cl.getResources(name); e.hasMoreElements(); ) {
            all.add(read(e.nextElement()));
        }
        return all;
    }

    /** Reads a resource without letting the JVM's JarFile cache pin the temp jar on Windows. */
    private static String read(URL url) throws Exception {
        assertNotNull(url);
        var connection = url.openConnection();
        connection.setUseCaches(false);
        try (var in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

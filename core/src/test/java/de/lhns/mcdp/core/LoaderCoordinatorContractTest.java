package de.lhns.mcdp.core;

import de.lhns.mcdp.deps.Manifest;
import de.lhns.mcdp.deps.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The parts of {@link LoaderCoordinator}'s contract that every platform adapter branches on but
 * that the coalescing tests never reach: the guard throws, the {@code null} return for an empty
 * promotion set, the cache key, and what a repeated {@code modId} does.
 */
class LoaderCoordinatorContractTest {

    private static void closeAll(LoaderCoordinator c) throws Exception {
        for (ModClassLoader m : c.allLoaders().values()) m.close();
        for (URLClassLoader l : c.libraryLoaders()) l.close();
    }

    private static Path modJar(Path tmp, String modId) throws Exception {
        Path p = tmp.resolve(modId + ".jar");
        SyntheticJar.writeJar(p, Map.of("com/example/" + modId + "/Entry.class",
                SyntheticJar.emptyClass("com/example/" + modId + "/Entry")));
        return p;
    }

    private static Path libJar(Path tmp, String name, String internal) throws Exception {
        Path p = tmp.resolve(name);
        SyntheticJar.writeJar(p, Map.of(internal + ".class", SyntheticJar.emptyClass(internal)));
        return p;
    }

    @Test
    void emptyPromotionSetYieldsNullRatherThanAnEmptyLoader() {
        // Every adapter writes `promoted != null ? promoted : parent`. If this ever returned an
        // empty loader instead of null, that expression would splice a useless loader into every
        // mod's delegation chain — and, worse, would look like a successful promotion.
        LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
        assertNull(coordinator.buildSharedLibraryLoader(List.of(), List.of()));
        assertTrue(coordinator.libraryLoaders().isEmpty(), "nothing may be cached for an empty set");
    }

    @Test
    void promotionRejectsMismatchedJarAndShaCounts(@TempDir Path tmp) throws Exception {
        // A caller that lost the jar/sha pairing would otherwise build a loader keyed by the
        // wrong SHAs — coalescing two genuinely different jar sets into one.
        Path jar = libJar(tmp, "lib.jar", "com/example/lib/V");
        LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.buildSharedLibraryLoader(List.of(jar), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.buildSharedLibraryLoader(List.of(), List.of("a".repeat(64))));
    }

    @Test
    void registerRejectsAManifestThatDoesNotMatchTheResolvedJars(@TempDir Path tmp) throws Exception {
        // manifest.libraries() and libraryJars are zipped by index in register(); a length
        // mismatch means the SHA at index i does not describe the jar at index i, so every
        // cache key past that point is a lie.
        Path lib = libJar(tmp, "lib.jar", "com/example/lib/V");
        Manifest oneLib = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", "http://x/lib.jar", "a".repeat(64))));
        LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.register("modA", oneLib, modJar(tmp, "modA"), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.register("modA", oneLib, modJar(tmp, "modA"), List.of(lib, lib)));
        assertTrue(coordinator.allLoaders().isEmpty(), "a rejected registration must leave no loader behind");
    }

    @Test
    void promotionKeyIgnoresTheOrderOfTheShaList(@TempDir Path tmp) throws Exception {
        // StdlibPromotion feeds this from map/stream order, which is not stable across boots.
        // If the key were order-sensitive the same promotion set would build two loaders and the
        // promoted stdlib would exist twice in one JVM.
        Path a = libJar(tmp, "a.jar", "com/example/stdlib/A");
        Path b = libJar(tmp, "b.jar", "com/example/stdlib/B");
        String shaA = Sha256.hex(Files.readAllBytes(a));
        String shaB = Sha256.hex(Files.readAllBytes(b));

        LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
        try {
            URLClassLoader first = coordinator.buildSharedLibraryLoader(List.of(a, b), List.of(shaA, shaB));
            URLClassLoader second = coordinator.buildSharedLibraryLoader(List.of(b, a), List.of(shaB, shaA));
            assertSame(first, second, "the SHA *set* is the key; list order is not part of it");
            assertEquals(1, coordinator.libraryLoaders().size());
        } finally {
            closeAll(coordinator);
        }
    }

    @Test
    void sameLibrarySetUnderDifferentPromotedParentsStaysSeparate(@TempDir Path tmp) throws Exception {
        // Two mods with an identical reduced-lib SHA set but different promoted-stdlib parents
        // must not share a library loader: sharing one would give the second mod the first mod's
        // stdlib, which is precisely the mismatch StdlibPromotion is meant to resolve.
        Path lib = libJar(tmp, "lib.jar", "com/example/lib/V");
        String libSha = Sha256.hex(Files.readAllBytes(lib));
        Manifest man = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", "http://x/lib.jar", libSha)));

        ClassLoader parentOne = new URLClassLoader("p1", new java.net.URL[0], getClass().getClassLoader());
        ClassLoader parentTwo = new URLClassLoader("p2", new java.net.URL[0], getClass().getClassLoader());

        LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
        try {
            ModClassLoader clA = coordinator.register("modA", man, List.of(modJar(tmp, "modA")), List.of(lib), parentOne);
            ModClassLoader clB = coordinator.register("modB", man, List.of(modJar(tmp, "modB")), List.of(lib), parentTwo);
            assertEquals(2, coordinator.libraryLoaders().size(),
                    "the library-loader cache key includes the promoted parent");
            assertNotSame(clA.loadClass("com.example.lib.V"), clB.loadClass("com.example.lib.V"));
        } finally {
            closeAll(coordinator);
            ((URLClassLoader) parentOne).close();
            ((URLClassLoader) parentTwo).close();
        }
    }

    @Test
    void reRegisteringAModIdReplacesTheLoaderAndStrandsTheOldOne(@TempDir Path tmp) throws Exception {
        // Current behaviour, pinned deliberately: modLoaders is a plain put, so the newest
        // registration wins lookups while the previous loader stays open, keeps its jar handles
        // (a hard pin on Windows) and stays reachable through McdpProvider's own id->loader map,
        // which is never cleaned up either. Any change here — putIfAbsent, a reject, a close of
        // the loser — should have to update this test on purpose.
        Path lib = libJar(tmp, "lib.jar", "com/example/lib/V");
        String libSha = Sha256.hex(Files.readAllBytes(lib));
        Manifest man = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", "http://x/lib.jar", libSha)));

        LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
        ModClassLoader first = null;
        try {
            first = coordinator.register("dup", man, modJar(tmp, "dupOne"), List.of(lib));
            ModClassLoader second = coordinator.register("dup", man, modJar(tmp, "dupTwo"), List.of(lib));

            assertNotSame(first, second, "a second register() builds a second loader");
            assertSame(second, coordinator.loaderFor("dup"), "the later registration wins lookups");
            assertEquals(1, coordinator.allLoaders().size(), "the map is keyed by modId, so it holds one");

            // The stranded loader is still fully alive — nothing closed it.
            assertNotNull(first.loadClass("com.example.dupOne.Entry"));
        } finally {
            if (first != null) first.close();
            closeAll(coordinator);
        }
    }
}

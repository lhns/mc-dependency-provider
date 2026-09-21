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

class LoaderCoordinatorTest {

    private static void closeAll(LoaderCoordinator c) throws Exception {
        for (ModClassLoader m : c.allLoaders().values()) m.close();
        for (URLClassLoader l : c.libraryLoaders()) l.close();
    }

    @Test
    void coalescesByShaAcrossMods(@TempDir Path tmp) throws Exception {
        String libInternal = "com/example/lib/Shared";
        Path libJar = tmp.resolve("lib.jar");
        SyntheticJar.writeJar(libJar, Map.of(libInternal + ".class", SyntheticJar.emptyClass(libInternal)));
        String libSha = Sha256.hex(Files.readAllBytes(libJar));

        Path modA = tmp.resolve("modA.jar");
        SyntheticJar.writeJar(modA, Map.of("com/example/modA/Entry.class",
                SyntheticJar.emptyClass("com/example/modA/Entry")));
        Path modB = tmp.resolve("modB.jar");
        SyntheticJar.writeJar(modB, Map.of("com/example/modB/Entry.class",
                SyntheticJar.emptyClass("com/example/modB/Entry")));

        Manifest manifestA = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", "http://x/lib.jar", libSha)));
        Manifest manifestB = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", "http://x/lib.jar", libSha)));

        LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
        try {
            ModClassLoader modAcl = coordinator.register("modA", manifestA, modA, List.of(libJar));
            ModClassLoader modBcl = coordinator.register("modB", manifestB, modB, List.of(libJar));

            Class<?> sharedA = modAcl.loadClass("com.example.lib.Shared");
            Class<?> sharedB = modBcl.loadClass("com.example.lib.Shared");

            assertSame(sharedA, sharedB, "same SHA → same library loader → same Class across mods");
            assertEquals(1, coordinator.libraryLoaders().size(), "expected single coalesced lib loader");
        } finally {
            closeAll(coordinator);
        }
    }

    /** Mod jar with a single Entry class, so each mod has its own distinct root. */
    private static Path modJar(Path tmp, String modId) throws Exception {
        Path p = tmp.resolve(modId + ".jar");
        SyntheticJar.writeJar(p, Map.of("com/example/" + modId + "/Entry.class",
                SyntheticJar.emptyClass("com/example/" + modId + "/Entry")));
        return p;
    }

    @Test
    void coalescesAcrossDistinctPathsWithIdenticalContent(@TempDir Path tmp) throws Exception {
        // ADR-0006's actual claim: the *SHA* is the key, not the cache path. Two mods that
        // resolved byte-identical content into different cache directories must still share a
        // loader, or Class identity splits for every cross-mod API using that library's types.
        String libInternal = "com/example/lib/Shared";
        Path libInA = tmp.resolve("cacheA/lib.jar");
        SyntheticJar.writeJar(libInA, Map.of(libInternal + ".class", SyntheticJar.emptyClass(libInternal)));
        Path libInB = tmp.resolve("cacheB/lib.jar");
        Files.createDirectories(libInB.getParent());
        Files.copy(libInA, libInB); // byte-identical -> same SHA, different path

        String libSha = Sha256.hex(Files.readAllBytes(libInA));
        assertEquals(libSha, Sha256.hex(Files.readAllBytes(libInB)));
        assertNotEquals(libInA, libInB);

        Manifest man = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", "http://x/lib.jar", libSha)));

        LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
        try {
            ModClassLoader clA = coordinator.register("modA", man, modJar(tmp, "modA"), List.of(libInA));
            ModClassLoader clB = coordinator.register("modB", man, modJar(tmp, "modB"), List.of(libInB));

            assertEquals(1, coordinator.libraryLoaders().size(),
                    "same SHA at two paths must still coalesce into one library loader");
            assertSame(clA.loadClass("com.example.lib.Shared"),
                    clB.loadClass("com.example.lib.Shared"));
        } finally {
            closeAll(coordinator);
        }
    }

    @Test
    void distinctShasAtTheSamePathStayDistinct(@TempDir Path tmp) throws Exception {
        // The mirror of the above: one path, two declared SHAs. Separates "different SHA" from
        // "different path" in the negative direction -- keying on the path would fuse these.
        Path lib = tmp.resolve("lib.jar");
        SyntheticJar.writeJar(lib, Map.of("com/example/lib/V.class",
                SyntheticJar.emptyClass("com/example/lib/V")));

        Manifest manA = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", "http://x/lib.jar", "a".repeat(64))));
        Manifest manB = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:2", "http://x/lib.jar", "b".repeat(64))));

        LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
        try {
            ModClassLoader clA = coordinator.register("modA", manA, modJar(tmp, "modA"), List.of(lib));
            ModClassLoader clB = coordinator.register("modB", manB, modJar(tmp, "modB"), List.of(lib));

            assertEquals(2, coordinator.libraryLoaders().size(),
                    "declared SHAs differ -> separate loaders even though the path is identical");
            assertNotSame(clA.loadClass("com.example.lib.V"), clB.loadClass("com.example.lib.V"));
        } finally {
            closeAll(coordinator);
        }
    }

    @Test
    void firstRegistrationsUrlsWinWhenDeclaredShasMatchButContentDiffers(@TempDir Path tmp) throws Exception {
        // Two different paths, different *content*, but both manifests declare the same SHA.
        // Current behaviour, pinned deliberately: computeIfAbsent means the loader is built from
        // whichever registration arrived first, and the second mod silently reads the first mod's
        // bytes -- its extra class is not on any loader it can reach.
        //
        // Intended contract: only the "SHA is the identity of the content" half (ADR-0006).
        // "First registration wins the URLs" is a consequence of the cache, not a designed
        // guarantee -- a manifest whose SHA does not match its jar is already lying. Recorded
        // here so a re-key (e.g. to paths) cannot change it unnoticed.
        String shared = "com/example/lib/Shared";
        Path libOne = tmp.resolve("cacheA/lib.jar");
        SyntheticJar.writeJar(libOne, Map.of(shared + ".class", SyntheticJar.emptyClass(shared)));
        Path libTwo = tmp.resolve("cacheB/lib.jar");
        SyntheticJar.writeJar(libTwo, Map.of(
                shared + ".class", SyntheticJar.emptyClass(shared),
                "com/example/lib/Marker.class", SyntheticJar.emptyClass("com/example/lib/Marker")));
        assertNotEquals(Sha256.hex(Files.readAllBytes(libOne)), Sha256.hex(Files.readAllBytes(libTwo)));

        String declaredSha = Sha256.hex(Files.readAllBytes(libOne));
        Manifest man = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", "http://x/lib.jar", declaredSha)));

        LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
        try {
            ModClassLoader clA = coordinator.register("modA", man, modJar(tmp, "modA"), List.of(libOne));
            ModClassLoader clB = coordinator.register("modB", man, modJar(tmp, "modB"), List.of(libTwo));

            assertEquals(1, coordinator.libraryLoaders().size(), "equal declared SHAs -> one loader");
            assertSame(clA.loadClass("com.example.lib.Shared"),
                    clB.loadClass("com.example.lib.Shared"));
            assertThrows(ClassNotFoundException.class,
                    () -> clB.loadClass("com.example.lib.Marker"),
                    "modB's own jar was never opened: the SHA-keyed loader came from modA's path");
        } finally {
            closeAll(coordinator);
        }
    }

    @Test
    void promotedParentSharesClassAcrossDistinctShaMods(@TempDir Path tmp) throws Exception {
        // Two mods pin DIFFERENT Scala 3 patch versions. SHA-keyed coalescing alone would give
        // them distinct stdlib loaders and distinct scala.runtime.Statics Class identities.
        // StdlibPromotion selects one winner and the adapter hands that loader to register() as
        // the parentLibLoader, so both mods see the same promoted Class.
        String promotedInternal = "com/example/stdlib/Statics";
        Path promotedJar = tmp.resolve("scala-library-winner.jar");
        SyntheticJar.writeJar(promotedJar, Map.of(promotedInternal + ".class",
                SyntheticJar.emptyClass(promotedInternal)));
        String promotedSha = Sha256.hex(Files.readAllBytes(promotedJar));

        Path libA = tmp.resolve("libA.jar");
        SyntheticJar.writeJar(libA, Map.of("com/example/libA/Foo.class",
                SyntheticJar.emptyClass("com/example/libA/Foo")));
        Path libB = tmp.resolve("libB.jar");
        SyntheticJar.writeJar(libB, Map.of("com/example/libB/Bar.class",
                SyntheticJar.emptyClass("com/example/libB/Bar")));
        String shaLibA = Sha256.hex(Files.readAllBytes(libA));
        String shaLibB = Sha256.hex(Files.readAllBytes(libB));

        Path modA = tmp.resolve("modA.jar");
        SyntheticJar.writeJar(modA, Map.of("com/example/modA/Entry.class",
                SyntheticJar.emptyClass("com/example/modA/Entry")));
        Path modB = tmp.resolve("modB.jar");
        SyntheticJar.writeJar(modB, Map.of("com/example/modB/Entry.class",
                SyntheticJar.emptyClass("com/example/modB/Entry")));

        Manifest reducedA = new Manifest("scala", List.of(),
                List.of(new Manifest.Library("c:libA:1", "http://x/libA.jar", shaLibA)));
        Manifest reducedB = new Manifest("scala", List.of(),
                List.of(new Manifest.Library("c:libB:1", "http://x/libB.jar", shaLibB)));

        LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
        try {
            URLClassLoader promoted = coordinator.buildSharedLibraryLoader(
                    List.of(promotedJar), List.of(promotedSha));
            assertNotNull(promoted);

            ModClassLoader clA = coordinator.register("modA", reducedA, List.of(modA), List.of(libA), promoted);
            ModClassLoader clB = coordinator.register("modB", reducedB, List.of(modB), List.of(libB), promoted);

            Class<?> sA = clA.loadClass("com.example.stdlib.Statics");
            Class<?> sB = clB.loadClass("com.example.stdlib.Statics");

            assertSame(sA, sB,
                    "promoted-stdlib loader is shared across mods with distinct reduced-lib SHAs");
            assertSame(promoted, sA.getClassLoader(),
                    "promoted class resolves through the shared promoted loader, not a per-mod loader");
        } finally {
            closeAll(coordinator);
        }
    }

    @Test
    void differentShasGiveDistinctClasses(@TempDir Path tmp) throws Exception {
        Path libA = tmp.resolve("libA.jar");
        SyntheticJar.writeJar(libA, Map.of("com/example/lib/V.class", SyntheticJar.emptyClass("com/example/lib/V")));
        Path libB = tmp.resolve("libB.jar");
        SyntheticJar.writeJar(libB, Map.of(
                "com/example/lib/V.class", SyntheticJar.emptyClass("com/example/lib/V"),
                "com/example/lib/Marker.class", SyntheticJar.emptyClass("com/example/lib/Marker")));

        String shaA = Sha256.hex(Files.readAllBytes(libA));
        String shaB = Sha256.hex(Files.readAllBytes(libB));
        assertNotEquals(shaA, shaB);

        Path modA = tmp.resolve("modA.jar");
        SyntheticJar.writeJar(modA, Map.of("com/example/modA/Entry.class",
                SyntheticJar.emptyClass("com/example/modA/Entry")));
        Path modB = tmp.resolve("modB.jar");
        SyntheticJar.writeJar(modB, Map.of("com/example/modB/Entry.class",
                SyntheticJar.emptyClass("com/example/modB/Entry")));

        Manifest manA = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", "http://x/a.jar", shaA)));
        Manifest manB = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:2", "http://x/b.jar", shaB)));

        LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
        try {
            ModClassLoader clA = coordinator.register("modA", manA, modA, List.of(libA));
            ModClassLoader clB = coordinator.register("modB", manB, modB, List.of(libB));

            Class<?> vA = clA.loadClass("com.example.lib.V");
            Class<?> vB = clB.loadClass("com.example.lib.V");
            assertNotSame(vA, vB, "different SHAs → different library loaders → different Class identities");
        } finally {
            closeAll(coordinator);
        }
    }
}

package io.github.mclibprovider.core;

import io.github.mclibprovider.deps.Manifest;
import io.github.mclibprovider.deps.Sha256;
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

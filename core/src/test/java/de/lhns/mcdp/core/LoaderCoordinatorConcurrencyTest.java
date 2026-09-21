package de.lhns.mcdp.core;

import de.lhns.mcdp.deps.Manifest;
import de.lhns.mcdp.deps.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the "thread-safe; adapters register from multiple threads during boot" claim on
 * {@link LoaderCoordinator}.
 * <p>
 * These are racing tests, not deterministic ones: the JDK gives no hook inside
 * {@code ConcurrentHashMap.computeIfAbsent}, so the only way to observe a check-then-act
 * regression is to have many threads arrive at the same key at the same instant. The barrier
 * makes them arrive together and {@link #ROUNDS} independent rounds make a single lucky
 * interleaving insufficient to pass — a check-then-act implementation has to win every round.
 */
class LoaderCoordinatorConcurrencyTest {

    /** Enough threads that at least two observe the empty map under a check-then-act bug. */
    private static final int THREADS = 8;
    /**
     * Independent rounds with a fresh coordinator each. One round already fails a check-then-act
     * implementation with very high probability; repeating drives the escape probability to
     * effectively zero while keeping the whole test well under a second.
     */
    private static final int ROUNDS = 20;

    private static void closeAll(LoaderCoordinator c) throws Exception {
        for (ModClassLoader m : c.allLoaders().values()) m.close();
        for (URLClassLoader l : c.libraryLoaders()) l.close();
    }

    /** Runs {@code body} on {@link #THREADS} threads released simultaneously; rethrows any failure. */
    private static void inLockstep(ThrowingIntConsumer body) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(THREADS);
        CountDownLatch done = new CountDownLatch(THREADS);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final int index = i;
            Thread t = new Thread(() -> {
                try {
                    startLine.await(10, TimeUnit.SECONDS);
                    body.accept(index);
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            }, "register-" + i);
            t.setDaemon(true);
            t.start();
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "registration threads did not finish");
        if (!failures.isEmpty()) {
            AssertionError e = new AssertionError("concurrent registration threw: " + failures.get(0));
            e.initCause(failures.get(0));
            throw e;
        }
    }

    private interface ThrowingIntConsumer {
        void accept(int index) throws Exception;
    }

    @Test
    void concurrentRegistrationsOfOneShaSetShareASingleLibraryLoader(@TempDir Path tmp) throws Exception {
        // If two threads each build their own LibraryClassLoader for the same SHA set, ADR-0006
        // coalescing stops holding: the library's Class identity silently splits per mod and
        // cross-mod API calls start throwing ClassCastException at runtime, far from here.
        String libInternal = "com/example/lib/Shared";
        Path libJar = tmp.resolve("lib.jar");
        SyntheticJar.writeJar(libJar, Map.of(libInternal + ".class", SyntheticJar.emptyClass(libInternal)));
        String libSha = Sha256.hex(Files.readAllBytes(libJar));

        List<Path> modJars = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Path p = tmp.resolve("mod" + i + ".jar");
            SyntheticJar.writeJar(p, Map.of("com/example/mod" + i + "/Entry.class",
                    SyntheticJar.emptyClass("com/example/mod" + i + "/Entry")));
            modJars.add(p);
        }
        Manifest manifest = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", "http://x/lib.jar", libSha)));

        for (int round = 0; round < ROUNDS; round++) {
            LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
            List<ModClassLoader> registered = new CopyOnWriteArrayList<>();
            try {
                inLockstep(i -> registered.add(
                        coordinator.register("mod" + i, manifest, modJars.get(i), List.of(libJar))));

                assertEquals(THREADS, registered.size());
                assertEquals(1, coordinator.libraryLoaders().size(),
                        "round " + round + ": one SHA set must yield exactly one library loader "
                                + "no matter how many threads register at once");

                Set<Class<?>> identities = Collections.newSetFromMap(new IdentityHashMap<>());
                for (ModClassLoader cl : registered) {
                    identities.add(cl.loadClass("com.example.lib.Shared"));
                }
                assertEquals(1, identities.size(),
                        "round " + round + ": the shared library class must have one identity");
            } finally {
                closeAll(coordinator);
            }
        }
    }

    @Test
    void concurrentSharedLibraryLoaderBuildsReturnTheSameInstance(@TempDir Path tmp) throws Exception {
        // Same argument for the stdlib-promotion entry point (ADR-0010): every adapter thread
        // that promotes the same winning jars must be handed the *same* loader, otherwise the
        // promoted scala-library classes split exactly the way promotion exists to prevent.
        Path promoted = tmp.resolve("scala-library.jar");
        SyntheticJar.writeJar(promoted, Map.of("com/example/stdlib/Statics.class",
                SyntheticJar.emptyClass("com/example/stdlib/Statics")));
        String sha = Sha256.hex(Files.readAllBytes(promoted));

        for (int round = 0; round < ROUNDS; round++) {
            LoaderCoordinator coordinator = new LoaderCoordinator(getClass().getClassLoader());
            List<URLClassLoader> built = new CopyOnWriteArrayList<>();
            try {
                inLockstep(i -> built.add(coordinator.buildSharedLibraryLoader(List.of(promoted), List.of(sha))));

                Set<URLClassLoader> identities = Collections.newSetFromMap(new IdentityHashMap<>());
                identities.addAll(built);
                assertEquals(1, identities.size(),
                        "round " + round + ": concurrent promotion must coalesce to one loader");
                assertEquals(1, coordinator.libraryLoaders().size(), "round " + round);
            } finally {
                closeAll(coordinator);
            }
        }
    }
}

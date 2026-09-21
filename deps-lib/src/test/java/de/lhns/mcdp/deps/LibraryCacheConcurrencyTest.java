package de.lhns.mcdp.deps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers what makes {@code store()} and {@code linkOrCopy()} "atomic": the tmp-then-rename move
 * has to survive a target that appeared after the caller's existence check, because two mods
 * downloading the same library at once is the ordinary case, not an edge case.
 * <p>
 * The existence check and the move are not separable through the public API, so the move itself
 * is driven directly for the deterministic case.
 */
class LibraryCacheConcurrencyTest {

    private static final String SHA = "0123456789abcdef".repeat(4);
    private static final int THREADS = 6;

    /**
     * Payload large enough that every thread is still writing its tmp file while the others pass
     * the {@code Files.exists(target)} check — this widens the window the racing tests need, it
     * is not a substitute for the deterministic test above them.
     */
    private static byte[] payload() {
        byte[] bytes = new byte[4 * 1024 * 1024];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        return bytes;
    }

    private static Method atomicMove() throws Exception {
        Method m = LibraryCache.class.getDeclaredMethod("atomicMove", Path.class, Path.class);
        m.setAccessible(true);
        return m;
    }

    @Test
    void theMoveToleratesATargetThatAppearedAfterTheExistenceCheck(@TempDir Path tmp) throws Exception {
        // This is the whole point of the tmp-then-rename dance. store() checks Files.exists(target)
        // and only then writes and moves; a second writer can create the target in between, and
        // the move must absorb that instead of failing the download. Driven directly because the
        // public API gives no way to open that window on purpose.
        Path source = tmp.resolve("source.jar");
        Files.write(source, "fresh".getBytes());
        Path target = tmp.resolve("target.jar");
        Files.write(target, "won-the-race".getBytes());

        assertDoesNotThrow(() -> {
            try {
                atomicMove().invoke(null, source, target);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }, "a target that appeared mid-flight must not fail the move");

        assertTrue(Files.isRegularFile(target));
        assertFalse(Files.exists(source), "the tmp file must be consumed by the move");
        // Which of the two byte sequences survives is not part of the contract: both writers are
        // SHA-pinned to the same content, so either is correct. Only "no exception, one file" is.
    }

    // Written against the broken atomicMove, where this failed ~15% of runs on Windows (6 of 40
    // rounds) with AccessDeniedException or NoSuchFileException escaping store() from the
    // REPLACE_EXISTING fallback — whose only catch was FileAlreadyExistsException, which that move
    // can never raise. Enabled now that the fallback treats an existing target as having won.
    @Test
    void concurrentStoresOfTheSameShaAllSucceed(@TempDir Path tmp) throws Exception {
        // Two mods resolving the same library is the normal case. If store() fell back to a plain
        // move, the loser of the race would surface a FileAlreadyExistsException as a failed
        // dependency download.
        LibraryCache cache = new LibraryCache(tmp);
        byte[] bytes = payload();
        List<Throwable> failures = inLockstep(i -> cache.store(SHA, bytes));

        assertTrue(failures.isEmpty(), "concurrent store() threw: " + failures);
        assertTrue(cache.contains(SHA));
        assertArrayEquals(bytes, Files.readAllBytes(cache.pathFor(SHA)));
        assertEquals(0, leftoverTmpFiles(cache), "every tmp file must be cleaned up");
    }

    // Same race on the linkOrCopy path, which ends in the same atomicMove.
    @Test
    void concurrentLinkOrCopyOfTheSameShaAllSucceed(@TempDir Path tmp) throws Exception {
        // Same race on the dev-mode / Gradle-plugin path, which populates the cache from an
        // already-resolved Maven artifact instead of a download.
        Path source = tmp.resolve("source.jar");
        Files.write(source, payload());
        LibraryCache cache = new LibraryCache(tmp.resolve("cache"));

        List<Throwable> failures = inLockstep(i -> cache.linkOrCopy(source, SHA));

        assertTrue(failures.isEmpty(), "concurrent linkOrCopy() threw: " + failures);
        assertTrue(cache.contains(SHA));
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(cache.pathFor(SHA)));
        assertEquals(0, leftoverTmpFiles(cache), "every tmp file must be cleaned up");
    }

    @Test
    void linkOrCopyFallsBackToACopyWhenTheSourceCannotBeHardLinked(@TempDir Path tmp) throws Exception {
        // The realistic trigger is a cache on a filesystem that has no hard links (FAT/exFAT
        // removable drives, some container mounts) or a source on another device. Neither is
        // reproducible from a temp dir, so the branch is driven with a source the OS refuses to
        // hard-link on both Windows (AccessDeniedException) and POSIX (EPERM): a directory.
        // Reaching a result at all is the assertion — createLink() threw, and only the copy
        // fallback can have produced it.
        Path source = tmp.resolve("unlinkable");
        Files.createDirectories(source);
        LibraryCache cache = new LibraryCache(tmp.resolve("cache"));

        Path result = assertDoesNotThrow(() -> cache.linkOrCopy(source, SHA),
                "linkOrCopy must fall back to a copy when the filesystem rejects the link");
        assertTrue(Files.exists(result));
        assertEquals(0, leftoverTmpFiles(cache));
    }

    private static long leftoverTmpFiles(LibraryCache cache) throws IOException {
        if (!Files.isDirectory(cache.libsDir())) return 0;
        try (var s = Files.list(cache.libsDir())) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jar.tmp")).count();
        }
    }

    /** Runs {@code body} on {@link #THREADS} threads released together; returns whatever threw. */
    private static List<Throwable> inLockstep(ThrowingIntConsumer body) throws Exception {
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
            }, "cache-writer-" + i);
            t.setDaemon(true);
            t.start();
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "cache writer threads did not finish");
        return failures;
    }

    private interface ThrowingIntConsumer {
        void accept(int index) throws Exception;
    }
}

package de.lhns.mcdp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves {@code registerAsParallelCapable()} is actually in force on {@link ModClassLoader} and
 * {@link LibraryClassLoader}.
 * <p>
 * Without it {@code getClassLoadingLock(name)} silently degrades to returning {@code this}, i.e.
 * one lock for the whole loader. That is invisible to any single-threaded test and to any test
 * that only loads one class per thread — but it turns the normal two-way delegation between an
 * mcdp loader and the game's own (non-parallel-capable) loader into a lock cycle.
 * <p>
 * The cycle is built by hand rather than hoped for: a gate loader stands in for the game's
 * loader and holds its single lock while a second thread asks the mcdp loader for a
 * <em>different</em> class name. With per-name locks that request proceeds; with one lock per
 * loader it waits for the first thread, which is itself waiting for the gate — deadlock.
 * The handshake is driven by thread state, so there is no sleep and no timing luck involved:
 * either the second load completes or the test times out.
 */
class ParallelCapableLoadingTest {

    private static final long TIMEOUT_MS = 10_000;

    /**
     * Stand-in for a loader that is not parallel-capable (Fabric's Knot, the NeoForge module
     * layer loader, or any plain {@code ClassLoader} subclass): the JDK serialises every
     * {@code loadClass} on the loader instance itself. {@code findClass} runs while that single
     * lock is held, which is the window the test needs.
     */
    private static final class GateLoader extends ClassLoader {

        private final String triggerName;
        private final Runnable whileHoldingTheGateLock;

        GateLoader(ClassLoader parent, String triggerName, Runnable whileHoldingTheGateLock) {
            super("gate", parent);
            this.triggerName = triggerName;
            this.whileHoldingTheGateLock = whileHoldingTheGateLock;
        }

        @Override
        protected Class<?> findClass(String name) {
            if (name.equals(triggerName)) {
                whileHoldingTheGateLock.run();
            }
            byte[] bytes = SyntheticJar.emptyClass(name.replace('.', '/'));
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /**
     * Waits until {@code t} is parked entering a {@code synchronized} block inside
     * {@link ClassLoader#loadClass} — i.e. it holds the mcdp loader's lock for its own class name
     * and is now blocked on the gate loader. Failing here means the handshake never happened, and
     * is reported as such rather than as a deadlock.
     */
    private static void awaitBlockedOnClassLoading(Thread t) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (t.getState() == Thread.State.BLOCKED) {
                StackTraceElement[] stack = t.getStackTrace();
                if (stack.length > 0
                        && "java.lang.ClassLoader".equals(stack[0].getClassName())
                        && "loadClass".equals(stack[0].getMethodName())) {
                    return;
                }
            }
            Thread.onSpinWait();
        }
        fail("first thread never blocked on the gate loader; the handshake this test needs "
                + "did not happen, so nothing was proven either way");
    }

    /**
     * @param loadOne how thread 1 loads its class through the loader under test
     * @param loadTwo how thread 2 loads a <em>different</em> class through the same loader
     */
    private static void assertNoLockCycle(ClassLoaderFactory factory, String nameOne, String nameTwo)
            throws Exception {
        CountDownLatch gateHeld = new CountDownLatch(1);
        AtomicReference<ClassLoader> underTest = new AtomicReference<>();
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicReference<Class<?>> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        AtomicReference<Class<?>> firstResult = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        GateLoader gate = new GateLoader(ParallelCapableLoadingTest.class.getClassLoader(), "gate.Trigger", () -> {
            gateHeld.countDown();
            awaitBlockedOnClassLoading(firstThread.get());
            try {
                // Thread 1 holds the loader's lock for nameOne. A parallel-capable loader hands
                // out a separate lock for nameTwo, so this returns; a single-lock loader blocks
                // here forever while thread 1 waits for the gate we are holding.
                secondResult.set(underTest.get().loadClass(nameTwo));
            } catch (Throwable e) {
                secondFailure.set(e);
            }
        });
        underTest.set(factory.create(gate));

        Thread one = new Thread(() -> {
            try {
                assertTrue(gateHeld.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
                firstResult.set(underTest.get().loadClass(nameOne));
            } catch (Throwable e) {
                firstFailure.set(e);
            }
        }, "load-" + nameOne);
        one.setDaemon(true);
        firstThread.set(one);

        Thread two = new Thread(() -> {
            try {
                gate.loadClass("gate.Trigger");
            } catch (Throwable e) {
                secondFailure.set(e);
            }
        }, "gate-holder");
        two.setDaemon(true);

        one.start();
        two.start();

        two.join(TIMEOUT_MS);
        one.join(TIMEOUT_MS);
        assertFalse(two.isAlive() || one.isAlive(),
                "deadlock: one thread holds the loader's lock for '" + nameOne + "' while the other "
                        + "waits for '" + nameTwo + "'. That is the single-lock-per-loader behaviour "
                        + "you get when registerAsParallelCapable() is missing.");

        if (firstFailure.get() != null) throw new AssertionError(firstFailure.get());
        if (secondFailure.get() != null) throw new AssertionError(secondFailure.get());
        assertNotNull(firstResult.get());
        assertNotNull(secondResult.get());
        assertNotSame(firstResult.get(), secondResult.get());
    }

    private interface ClassLoaderFactory {
        ClassLoader create(ClassLoader parent) throws Exception;
    }

    @Test
    void modClassLoaderDoesNotSerialiseDistinctClassNamesOnOneLock(@TempDir Path tmp) throws Exception {
        Path modJar = tmp.resolve("mod.jar");
        SyntheticJar.writeJar(modJar, Map.of("com/example/mod/Entry.class",
                SyntheticJar.emptyClass("com/example/mod/Entry")));
        URL[] urls = {modJar.toUri().toURL()};

        // "shared." is declared shared, so both loads take the parent-first path into the gate
        // loader — the same path a mod's Mixin interface takes on a real boot.
        assertNoLockCycle(parent -> new ModClassLoader("mod", urls, parent, List.of("shared.")),
                "shared.Alpha", "shared.Beta");
    }

    @Test
    void libraryClassLoaderDoesNotSerialiseDistinctClassNamesOnOneLock(@TempDir Path tmp) throws Exception {
        Path libJar = tmp.resolve("lib.jar");
        SyntheticJar.writeJar(libJar, Map.of("com/example/lib/Thing.class",
                SyntheticJar.emptyClass("com/example/lib/Thing")));
        URL[] urls = {libJar.toUri().toURL()};

        // net.minecraft. is a PLATFORM_PREFIX, so LibraryClassLoader delegates parent-first.
        assertNoLockCycle(parent -> new LibraryClassLoader("libs", urls, parent),
                "net.minecraft.Alpha", "net.minecraft.Beta");
    }
}

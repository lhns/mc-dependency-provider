package de.lhns.mcdp.fabric;

import de.lhns.mcdp.deps.ProgressListener;
import org.slf4j.Logger;

/**
 * SLF4J-backed {@link ProgressListener} for the Fabric adapter. Every event is emitted as a
 * structured log line keyed by mod id; per-library start lines land at DEBUG (avoid wall-of-text
 * by default) while every other event is INFO so users see the start/end summary and per-library
 * completions in {@code latest.log}.
 *
 * <p>Fabric's PreLaunch runs before any in-game UI exists, so the launcher's stderr/stdout tail
 * is the only feedback channel during dep downloads. The adapter prints one stdout banner at
 * {@link #started} for users running through a terminal who can't see {@code latest.log} yet.
 */
final class LoggingProgressListener implements ProgressListener {

    private final Logger log;
    private final String modId;
    private long startNanos;

    LoggingProgressListener(Logger log, String modId) {
        this.log = log;
        this.modId = modId;
    }

    @Override
    public void started(int totalLibraries, long totalBytesEstimate) {
        startNanos = System.nanoTime();
        if (totalLibraries == 0) return;
        log.info("mcdp[{}]: resolving {} {}", modId, totalLibraries,
                totalLibraries == 1 ? "library" : "libraries");
        // Stderr banner so terminal users see something pre-splash. One line, regardless of count.
        System.err.println("[mcdp] resolving " + totalLibraries
                + (totalLibraries == 1 ? " library" : " libraries")
                + " for " + modId + " (this may take a moment on first launch)");
    }

    @Override
    public void libraryStarted(int index, int total, String coords, long expectedBytes) {
        log.debug("mcdp[{}]: ({}/{}) {} ...", modId, index, total, coords);
    }

    @Override
    public void libraryFinished(int index, int total, String coords, long actualBytes, boolean fromCache) {
        log.info("mcdp[{}]: ({}/{}) {} {}",
                modId, index, total, coords,
                fromCache ? "(cached)" : humanBytes(actualBytes));
    }

    @Override
    public void finished() {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("mcdp[{}]: resolved in {} ms", modId, elapsedMs);
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

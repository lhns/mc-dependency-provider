package de.lhns.mcdp.neoforge;

import de.lhns.mcdp.deps.ProgressListener;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * SLF4J-backed {@link ProgressListener} for the NeoForge adapter. Mirrors the Fabric variant's
 * log shape, plus pushes a short message into FML's startup notification rail when available.
 *
 * <p>{@code StartupNotificationManager} is reached reflectively, by name, so this one file
 * compiles unchanged on every NeoForge band: if the class cannot be loaded (or its static
 * {@code addModMessage} call raises), the UI side no-ops and we fall back to log-only. The class
 * lives in {@code net.neoforged.fml.loading.progress} on loader 3.0.45, 4.0.42 and 10.0.36 alike.
 * It was once looked up as {@code net.neoforged.fml.StartupNotificationManager}, a class that
 * exists on none of them — so the "UI side" was a silent no-op on every band until
 * {@code LoggingProgressListenerTest} pinned the name against each band's real loader jar.
 */
final class LoggingProgressListener implements ProgressListener {

    private static final String SNM_FQN =
            "net.neoforged.fml.loading.progress.StartupNotificationManager";

    private final Logger log;
    private final String modId;
    private long startNanos;
    private final boolean snmAvailable;

    LoggingProgressListener(Logger log, String modId) {
        this.log = log;
        this.modId = modId;
        this.snmAvailable = isClassLoadable();
    }

    private static boolean isClassLoadable() {
        try {
            Class.forName(SNM_FQN, false, LoggingProgressListener.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private void notifyUi(String message) {
        if (!snmAvailable) return;
        try {
            // Reflective call avoids a hard link: even with the class
            // loadable, addModMessage may throw if FML's notification rail isn't initialized yet.
            Class<?> snm = Class.forName(SNM_FQN);
            snm.getMethod("addModMessage", String.class).invoke(null, message);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Best-effort UI surface — log channel still carries the same content.
        }
    }

    @Override
    public void started(int totalLibraries, long totalBytesEstimate) {
        startNanos = System.nanoTime();
        if (totalLibraries == 0) return;
        log.info("mcdp[{}]: resolving {} {}", modId, totalLibraries,
                totalLibraries == 1 ? "library" : "libraries");
        notifyUi("mcdp: resolving deps for " + modId);
    }

    @Override
    public void libraryStarted(int index, int total, String coords, long expectedBytes) {
        log.debug("mcdp[{}]: ({}/{}) {} ...", modId, index, total, coords);
        notifyUi("mcdp: " + modId + " (" + index + "/" + total + ") " + coords);
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

    /** {@link Locale#ROOT}, so a de_DE machine logs {@code 1.5 KB} rather than {@code 1,5 KB}. */
    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

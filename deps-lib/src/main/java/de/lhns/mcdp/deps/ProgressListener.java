package de.lhns.mcdp.deps;

/**
 * Callback surface for {@link ManifestConsumer#resolveAll(Manifest, ProgressListener)}.
 *
 * <p>Adapter modules wire concrete implementations that log to SLF4J / push to NeoForge's
 * {@code StartupNotificationManager} / pump a Fabric splash hook (when one exists). Keeping
 * the interface here means {@code deps-lib} stays SLF4J-free; nothing in this module imports
 * a logging framework.
 *
 * <p>All callbacks are invoked on the resolving thread in this order:
 * <ol>
 *   <li>{@link #started(int, long)} once per {@code resolveAll}.</li>
 *   <li>{@link #libraryStarted(int, int, String, long)} → {@link #libraryFinished(int, int,
 *       String, long, boolean)} once per library, in manifest order.</li>
 *   <li>{@link #finished()} once at the end (also fires on the empty-manifest fast path).</li>
 * </ol>
 *
 * <p>If a download fails, {@link #libraryFinished} is NOT called for that library; the
 * exception propagates out of {@code resolveAll}. {@link #finished()} is still fired in a
 * {@code finally} so listeners holding state (UI handles, timers) clean up.
 */
public interface ProgressListener {

    /**
     * Fired once before the first library is touched.
     *
     * @param totalLibraries number of libraries in the manifest (may be 0).
     * @param totalBytesEstimate sum of expected bytes across all libraries, or {@code 0}
     *                           if the manifest doesn't carry size hints. {@code deps-lib}
     *                           v0.1's {@link Manifest.Library} has no size field, so this
     *                           is currently always {@code 0}; the parameter is reserved for
     *                           a future schema bump.
     */
    void started(int totalLibraries, long totalBytesEstimate);

    /**
     * Fired before either a cache lookup or an HTTP fetch for the named library.
     *
     * @param index 1-based position in the manifest.
     * @param total same value as {@code totalLibraries} above; passed for convenience.
     * @param coords human-readable Maven coordinates ({@code group:artifact:version}).
     * @param expectedBytes size hint, or {@code 0} if unknown.
     */
    void libraryStarted(int index, int total, String coords, long expectedBytes);

    /**
     * Fired after the library is ready on disk and SHA-verified.
     *
     * @param fromCache {@code true} if the library was already in the cache (no network call);
     *                  {@code false} if it was just downloaded.
     */
    void libraryFinished(int index, int total, String coords, long actualBytes, boolean fromCache);

    /** Fired exactly once at the end of {@code resolveAll}, even on failure. */
    void finished();

    /** All-empty default. Used when callers don't supply a listener. */
    ProgressListener NOOP = new ProgressListener() {
        @Override public void started(int totalLibraries, long totalBytesEstimate) {}
        @Override public void libraryStarted(int index, int total, String coords, long expectedBytes) {}
        @Override public void libraryFinished(int index, int total, String coords, long actualBytes, boolean fromCache) {}
        @Override public void finished() {}
    };
}

package de.lhns.mcdp.deps;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Given a {@link Manifest} and a {@link LibraryCache}, ensures every library is present on disk
 * by SHA. Missing libraries are downloaded from their URL and verified. Hash mismatches abort.
 * <p>
 * Runtime-only dependency graph: {@code java.net.http} + {@code java.security.MessageDigest}. No
 * Aether, no Coursier, no external runtime deps. {@link ManifestIo} reads via the in-tree
 * {@link MiniToml}.
 */
public final class ManifestConsumer {

    private final LibraryCache cache;
    private final HttpClient http;
    private final RepoWhitelist whitelist;

    public ManifestConsumer(LibraryCache cache) {
        this(cache, defaultClient(), RepoWhitelist.fromEnv());
    }

    public ManifestConsumer(LibraryCache cache, HttpClient http) {
        this(cache, http, RepoWhitelist.fromEnv());
    }

    public ManifestConsumer(LibraryCache cache, HttpClient http, RepoWhitelist whitelist) {
        this.cache = cache;
        this.http = http;
        this.whitelist = whitelist;
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Resolves every library in the manifest. Returns the on-disk path of each in manifest order.
     * Throws if any SHA-256 doesn't match the expected value.
     */
    public List<Path> resolveAll(Manifest manifest) throws IOException {
        return resolveAll(manifest, ProgressListener.NOOP);
    }

    /**
     * Same as {@link #resolveAll(Manifest)} but invokes {@code listener} as each library is
     * processed (see {@link ProgressListener} for the ordering contract). {@code listener.finished()}
     * always fires, even if a download throws.
     */
    public List<Path> resolveAll(Manifest manifest, ProgressListener listener) throws IOException {
        List<Manifest.Library> libs = manifest.libraries();
        int total = libs.size();
        listener.started(total, 0L);
        try {
            List<Path> paths = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                Manifest.Library lib = libs.get(i);
                listener.libraryStarted(i + 1, total, lib.coords(), 0L);
                boolean cached = cache.contains(lib.sha256());
                Path p = resolve(lib);
                long actualBytes;
                try {
                    actualBytes = java.nio.file.Files.size(p);
                } catch (IOException ignored) {
                    actualBytes = 0L;
                }
                listener.libraryFinished(i + 1, total, lib.coords(), actualBytes, cached);
                paths.add(p);
            }
            return paths;
        } finally {
            listener.finished();
        }
    }

    public Path resolve(Manifest.Library lib) throws IOException {
        if (cache.contains(lib.sha256())) {
            return cache.pathFor(lib.sha256());
        }
        if (whitelist != null && !whitelist.allows(lib.url())) {
            throw new IOException(whitelist.rejectionMessage(lib.url())
                    + " (coords=" + lib.coords() + ")");
        }
        byte[] bytes = download(lib.url());
        String actual = Sha256.hex(bytes);
        if (!actual.equalsIgnoreCase(lib.sha256())) {
            throw new IOException("SHA-256 mismatch for " + lib.coords()
                    + " (url=" + lib.url() + "): expected " + lib.sha256() + ", got " + actual);
        }
        return cache.store(lib.sha256(), bytes);
    }

    private byte[] download(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofMinutes(2))
                .build();
        HttpResponse<byte[]> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + url, e);
        } catch (IOException e) {
            // JDK's HttpClient often throws ConnectException / UnknownHostException with a null
            // message. Wrapping ensures the user sees the URL in the log.
            throw new IOException("Failed to download " + url + ": " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " — " + e.getMessage() : ""), e);
        }
        if (res.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + res.statusCode() + " downloading " + url);
        }
        return res.body();
    }
}

package io.github.mclibprovider.deps;

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
 * Aether, no Coursier, no external deps besides tomlj (via {@link ManifestIo}).
 */
public final class ManifestConsumer {

    private final LibraryCache cache;
    private final HttpClient http;

    public ManifestConsumer(LibraryCache cache) {
        this(cache, defaultClient());
    }

    public ManifestConsumer(LibraryCache cache, HttpClient http) {
        this.cache = cache;
        this.http = http;
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
        List<Path> paths = new ArrayList<>(manifest.libraries().size());
        for (Manifest.Library lib : manifest.libraries()) {
            paths.add(resolve(lib));
        }
        return paths;
    }

    public Path resolve(Manifest.Library lib) throws IOException {
        if (cache.contains(lib.sha256())) {
            return cache.pathFor(lib.sha256());
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
        }
        if (res.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + res.statusCode() + " downloading " + url);
        }
        return res.body();
    }
}

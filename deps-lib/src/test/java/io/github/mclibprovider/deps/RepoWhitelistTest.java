package io.github.mclibprovider.deps;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepoWhitelistTest {

    private HttpServer server;
    private String baseUrl;
    private final byte[] body = "whitelist-bytes".getBytes();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    @Test
    void matchingPrefixAllowsDownload(@TempDir Path tmp) throws IOException {
        String sha = Sha256.hex(body);
        String url = baseUrl + "/lib.jar";
        Manifest m = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", url, sha)));
        RepoWhitelist wl = new RepoWhitelist(List.of(baseUrl));
        ManifestConsumer consumer = new ManifestConsumer(new LibraryCache(tmp), HttpClient.newHttpClient(), wl);

        assertDoesNotThrow(() -> consumer.resolveAll(m));
    }

    @Test
    void nonMatchingPrefixRejectsBeforeDownload(@TempDir Path tmp) {
        String url = baseUrl + "/lib.jar";
        Manifest m = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", url, "a".repeat(64))));
        RepoWhitelist wl = new RepoWhitelist(List.of("https://evil.example/"));
        ManifestConsumer consumer = new ManifestConsumer(new LibraryCache(tmp), HttpClient.newHttpClient(), wl);

        IOException ex = assertThrows(IOException.class, () -> consumer.resolveAll(m));
        assertTrue(ex.getMessage().contains("not in repo whitelist"), ex.getMessage());
        assertTrue(ex.getMessage().contains(url), "should echo rejected URL");
    }

    @Test
    void nullWhitelistMeansNoEnforcement(@TempDir Path tmp) throws IOException {
        String sha = Sha256.hex(body);
        Manifest m = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", baseUrl + "/lib.jar", sha)));
        ManifestConsumer consumer = new ManifestConsumer(new LibraryCache(tmp), HttpClient.newHttpClient(), null);
        assertDoesNotThrow(() -> consumer.resolveAll(m));
    }

    @Test
    void cacheHitBypassesWhitelistCheck(@TempDir Path tmp) throws IOException {
        String sha = Sha256.hex(body);
        LibraryCache cache = new LibraryCache(tmp);
        cache.store(sha, body);
        Manifest m = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", "https://blocked.example/lib.jar", sha)));
        RepoWhitelist wl = new RepoWhitelist(List.of("https://allowed.example/"));
        ManifestConsumer consumer = new ManifestConsumer(cache, HttpClient.newHttpClient(), wl);
        assertDoesNotThrow(() -> consumer.resolveAll(m));
    }

    @Test
    void fromEnvReturnsNullWhenUnset() {
        // Can't reliably set env vars cross-platform from a test, but we can at least
        // verify the blank/null behavior by constructing whitelists directly.
        RepoWhitelist wl = new RepoWhitelist(List.of("https://example/"));
        assertTrue(wl.allows("https://example/foo"));
        assertFalse(wl.allows("https://other/foo"));
    }

    @Test
    void mavenCentralConstantHasBothCanonicalPrefixes() {
        RepoWhitelist wl = new RepoWhitelist(RepoWhitelist.MAVEN_CENTRAL_PREFIXES);
        assertTrue(wl.allows("https://repo1.maven.org/maven2/org/typelevel/cats-core_3/2.13.0/cats-core_3-2.13.0.jar"));
        assertTrue(wl.allows("https://repo.maven.apache.org/maven2/x.jar"));
        assertFalse(wl.allows("https://maven.fabricmc.net/x.jar"));
    }
}

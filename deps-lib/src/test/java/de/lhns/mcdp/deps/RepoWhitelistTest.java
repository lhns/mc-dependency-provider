package de.lhns.mcdp.deps;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

class RepoWhitelistTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final byte[] body = "whitelist-bytes".getBytes();

    @BeforeEach
    void startServer() throws IOException {
        requestCount.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    /** Records whether each library came from the cache; the only observable proof of the branch taken. */
    private static final class CacheFlagListener implements ProgressListener {
        final List<Boolean> fromCache = new ArrayList<>();
        @Override public void started(int total, long bytes) {}
        @Override public void libraryStarted(int i, int total, String coords, long expected) {}
        @Override public void libraryFinished(int i, int total, String coords, long actual, boolean cached) {
            fromCache.add(cached);
        }
        @Override public void finished() {}
    }

    @Test
    void matchingPrefixAllowsDownload(@TempDir Path tmp) throws IOException {
        String sha = Sha256.hex(body);
        String url = baseUrl + "/lib.jar";
        Manifest m = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", url, sha)));
        RepoWhitelist wl = new RepoWhitelist(List.of(baseUrl));
        LibraryCache cache = new LibraryCache(tmp);
        ManifestConsumer consumer = new ManifestConsumer(cache, HttpClient.newHttpClient(), wl);

        List<Path> paths = consumer.resolveAll(m);

        // "Didn't throw" is also true of a resolveAll that does nothing at all, so assert the jar
        // actually landed: one path, at the SHA-derived cache location, with the served bytes.
        assertEquals(1, paths.size());
        assertEquals(cache.pathFor(sha), paths.get(0));
        assertArrayEquals(body, Files.readAllBytes(paths.get(0)));
        assertTrue(cache.contains(sha), "jar should be in the cache after an allowed download");
        assertEquals(1, requestCount.get(), "allowed URL must actually be fetched");
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
        assertEquals(0, requestCount.get(), "rejection must happen before the request is sent");
    }

    @Test
    void nullWhitelistMeansNoEnforcement(@TempDir Path tmp) throws IOException {
        String sha = Sha256.hex(body);
        LibraryCache cache = new LibraryCache(tmp);
        Manifest m = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", baseUrl + "/lib.jar", sha)));
        ManifestConsumer consumer = new ManifestConsumer(cache, HttpClient.newHttpClient(), null);

        List<Path> paths = consumer.resolveAll(m);

        // No whitelist must mean the download proceeds — not merely that nothing threw.
        assertEquals(1, paths.size());
        assertEquals(cache.pathFor(sha), paths.get(0));
        assertArrayEquals(body, Files.readAllBytes(paths.get(0)));
        assertEquals(1, requestCount.get(), "no whitelist must not suppress the fetch");
    }

    @Test
    void cacheHitBypassesWhitelistCheck(@TempDir Path tmp) throws IOException {
        String sha = Sha256.hex(body);
        LibraryCache cache = new LibraryCache(tmp);
        Path cached = cache.store(sha, body);
        Manifest m = new Manifest("java", List.of(),
                List.of(new Manifest.Library("c:lib:1", "https://blocked.example/lib.jar", sha)));
        RepoWhitelist wl = new RepoWhitelist(List.of("https://allowed.example/"));
        ManifestConsumer consumer = new ManifestConsumer(cache, HttpClient.newHttpClient(), wl);
        CacheFlagListener listener = new CacheFlagListener();

        List<Path> paths = consumer.resolveAll(m, listener);

        // The URL is both unreachable and blocked, so the only way to succeed is the cache
        // branch: assert we got the pre-seeded file back and that the resolver reported it
        // as a cache hit rather than having gone to the network.
        assertEquals(1, paths.size());
        assertEquals(cached, paths.get(0));
        assertArrayEquals(body, Files.readAllBytes(paths.get(0)));
        assertEquals(List.of(true), listener.fromCache, "must be reported as a cache hit");
        assertEquals(0, requestCount.get());
    }

    @Test
    void fromEnvReturnsNullWhenUnset() {
        // Guard rather than assume: if the developer's shell exports the var, the assertion
        // below would be testing the wrong branch.
        assumeTrue(System.getenv(RepoWhitelist.ENV_VAR) == null
                || System.getenv(RepoWhitelist.ENV_VAR).isBlank());

        // Unset must mean no enforcement. A fromEnv() that defaulted to any non-null whitelist
        // would break every install pointing at a non-Central repo.
        assertNull(RepoWhitelist.fromEnv());
    }

    @Test
    void parseSplitsOnCommas() {
        RepoWhitelist wl = RepoWhitelist.parse("https://a/,https://b/");
        assertEquals(List.of("https://a/", "https://b/"), wl.prefixes());
    }

    @Test
    void parseTrimsSurroundingWhitespace() {
        // Env vars get pasted into shell scripts and Docker compose files with stray spaces
        // around the separators; an untrimmed " https://b/" would never match any URL.
        RepoWhitelist wl = RepoWhitelist.parse("  https://a/ ,\thttps://b/  ");
        assertEquals(List.of("https://a/", "https://b/"), wl.prefixes());
        assertTrue(wl.allows("https://b/x.jar"));
    }

    @Test
    void parseDropsEmptyEntriesButKeepsTheRest() {
        // A trailing or doubled comma must not inject an empty prefix — "".startsWith
        // matches every URL, which would silently disable enforcement entirely.
        RepoWhitelist wl = RepoWhitelist.parse("https://a/,,https://b/,");
        assertEquals(List.of("https://a/", "https://b/"), wl.prefixes());
        assertFalse(wl.allows("https://evil.example/x.jar"));
    }

    @Test
    void parseKeepsPrefixOrder() {
        assertEquals(List.of("https://c/", "https://a/", "https://b/"),
                RepoWhitelist.parse("https://c/,https://a/,https://b/").prefixes());
    }

    @Test
    void parseReturnsNullForNoMeaningfulInput() {
        // All five collapse to "no enforcement" rather than to an empty whitelist, which
        // would block every download. null is the unset env var; the rest are fat-finger input.
        assertNull(RepoWhitelist.parse(null), "unset");
        assertNull(RepoWhitelist.parse(""), "empty");
        assertNull(RepoWhitelist.parse("   \t"), "blank");
        assertNull(RepoWhitelist.parse(",,"), "commas only");
        assertNull(RepoWhitelist.parse(" , "), "commas and spaces only");
    }

    @Test
    void parseResultIsImmutableAndEnforcing() {
        RepoWhitelist wl = RepoWhitelist.parse("https://repo1.maven.org/maven2/");
        assertTrue(wl.allows("https://repo1.maven.org/maven2/x.jar"));
        assertFalse(wl.allows("https://maven.fabricmc.net/x.jar"));
        assertThrows(UnsupportedOperationException.class, () -> wl.prefixes().add("https://evil/"));
    }

    @Test
    void mavenCentralConstantHasBothCanonicalPrefixes() {
        RepoWhitelist wl = new RepoWhitelist(RepoWhitelist.MAVEN_CENTRAL_PREFIXES);
        assertTrue(wl.allows("https://repo1.maven.org/maven2/org/typelevel/cats-core_3/2.13.0/cats-core_3-2.13.0.jar"));
        assertTrue(wl.allows("https://repo.maven.apache.org/maven2/x.jar"));
        assertFalse(wl.allows("https://maven.fabricmc.net/x.jar"));
    }

    @Test
    void prefixMatchingIsExactAndNotSubstring() {
        RepoWhitelist wl = new RepoWhitelist(List.of("https://example/"));
        assertTrue(wl.allows("https://example/foo"));
        assertFalse(wl.allows("https://other/foo"));
        // A host that merely *contains* an allowed prefix later in the URL must not pass.
        assertFalse(wl.allows("https://evil.example.com/?u=https://example/foo"));
        assertFalse(wl.allows("http://example/foo"), "scheme is part of the prefix");
    }

    @Test
    void emptyPrefixListAllowsNothing() {
        // Distinct from a null whitelist: constructed-but-empty must enforce, not wave through.
        RepoWhitelist wl = new RepoWhitelist(List.of());
        assertFalse(wl.allows("https://repo1.maven.org/maven2/x.jar"));
    }
}

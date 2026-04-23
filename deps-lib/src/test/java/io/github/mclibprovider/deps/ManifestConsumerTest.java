package io.github.mclibprovider.deps;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ManifestConsumerTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requestCount = new AtomicInteger();
    private byte[] servedBody = "hello-jar-body".getBytes();

    @BeforeEach
    void startServer() throws IOException {
        requestCount.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = servedBody;
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void downloadsAndVerifiesSha(@TempDir Path tmp) throws IOException {
        String sha = Sha256.hex(servedBody);
        Manifest manifest = new Manifest("java", List.of(), List.of(
                new Manifest.Library("c:lib:1", baseUrl + "/lib.jar", sha)));
        LibraryCache cache = new LibraryCache(tmp);
        ManifestConsumer consumer = new ManifestConsumer(cache);

        List<Path> paths = consumer.resolveAll(manifest);
        assertEquals(1, paths.size());
        assertArrayEquals(servedBody, Files.readAllBytes(paths.get(0)));
    }

    @Test
    void cacheHitSkipsDownload(@TempDir Path tmp) throws IOException {
        String sha = Sha256.hex(servedBody);
        Manifest manifest = new Manifest("java", List.of(), List.of(
                new Manifest.Library("c:lib:1", baseUrl + "/lib.jar", sha)));
        LibraryCache cache = new LibraryCache(tmp);
        cache.store(sha, servedBody);
        ManifestConsumer consumer = new ManifestConsumer(cache);

        consumer.resolveAll(manifest);
        assertEquals(0, requestCount.get(), "should not have hit the server");
    }

    @Test
    void shaMismatchFailsLoudly(@TempDir Path tmp) {
        String wrongSha = "f".repeat(64);
        Manifest manifest = new Manifest("java", List.of(), List.of(
                new Manifest.Library("c:lib:1", baseUrl + "/lib.jar", wrongSha)));
        ManifestConsumer consumer = new ManifestConsumer(new LibraryCache(tmp));
        IOException ex = assertThrows(IOException.class, () -> consumer.resolveAll(manifest));
        assertTrue(ex.getMessage().contains("SHA-256 mismatch"), ex.getMessage());
    }

    @Test
    void httpErrorIsReported(@TempDir Path tmp) {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        Manifest manifest = new Manifest("java", List.of(), List.of(
                new Manifest.Library("c:lib:1", baseUrl + "/missing.jar", "a".repeat(64))));
        ManifestConsumer consumer = new ManifestConsumer(new LibraryCache(tmp));
        IOException ex = assertThrows(IOException.class, () -> consumer.resolveAll(manifest));
        assertTrue(ex.getMessage().contains("HTTP 404"), ex.getMessage());
    }
}

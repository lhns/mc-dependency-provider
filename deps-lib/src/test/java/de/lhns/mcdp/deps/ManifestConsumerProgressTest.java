package de.lhns.mcdp.deps;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestConsumerProgressTest {

    private HttpServer server;
    private String baseUrl;
    private final byte[] body1 = "first-jar-body".getBytes();
    private final byte[] body2 = "second-jar-body-and-then-some".getBytes();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lib1.jar", ex -> {
            ex.sendResponseHeaders(200, body1.length);
            try (var os = ex.getResponseBody()) { os.write(body1); }
        });
        server.createContext("/lib2.jar", ex -> {
            ex.sendResponseHeaders(200, body2.length);
            try (var os = ex.getResponseBody()) { os.write(body2); }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    private static final class RecordingListener implements ProgressListener {
        final List<String> events = new ArrayList<>();
        @Override public void started(int total, long bytes) {
            events.add("started:" + total);
        }
        @Override public void libraryStarted(int i, int total, String coords, long expected) {
            events.add("libStarted:" + i + "/" + total + ":" + coords);
        }
        @Override public void libraryFinished(int i, int total, String coords, long actual, boolean cached) {
            events.add("libFinished:" + i + "/" + total + ":" + coords + ":bytes=" + actual + ":cached=" + cached);
        }
        @Override public void finished() {
            events.add("finished");
        }
    }

    @Test
    void firesEventsInOrderOnHappyPath(@TempDir Path tmp) throws IOException {
        String sha1 = Sha256.hex(body1);
        String sha2 = Sha256.hex(body2);
        Manifest manifest = new Manifest("java", List.of(), List.of(
                new Manifest.Library("c:lib1:1", baseUrl + "/lib1.jar", sha1),
                new Manifest.Library("c:lib2:1", baseUrl + "/lib2.jar", sha2)));
        ManifestConsumer consumer = new ManifestConsumer(new LibraryCache(tmp));
        RecordingListener listener = new RecordingListener();

        consumer.resolveAll(manifest, listener);

        assertEquals(List.of(
                "started:2",
                "libStarted:1/2:c:lib1:1",
                "libFinished:1/2:c:lib1:1:bytes=" + body1.length + ":cached=false",
                "libStarted:2/2:c:lib2:1",
                "libFinished:2/2:c:lib2:1:bytes=" + body2.length + ":cached=false",
                "finished"
        ), listener.events);
    }

    @Test
    void cacheHitsReportFromCache(@TempDir Path tmp) throws IOException {
        String sha1 = Sha256.hex(body1);
        LibraryCache cache = new LibraryCache(tmp);
        cache.store(sha1, body1);
        Manifest manifest = new Manifest("java", List.of(), List.of(
                new Manifest.Library("c:lib1:1", baseUrl + "/lib1.jar", sha1)));
        RecordingListener listener = new RecordingListener();

        new ManifestConsumer(cache).resolveAll(manifest, listener);

        assertTrue(listener.events.stream().anyMatch(e -> e.contains("cached=true")),
                "should report cached=true; got " + listener.events);
    }

    @Test
    void emptyManifestStillFiresStartedAndFinished(@TempDir Path tmp) throws IOException {
        Manifest manifest = new Manifest("java", List.of(), List.of());
        RecordingListener listener = new RecordingListener();

        new ManifestConsumer(new LibraryCache(tmp)).resolveAll(manifest, listener);

        assertEquals(List.of("started:0", "finished"), listener.events);
    }

    @Test
    void finishedFiresEvenWhenDownloadFails(@TempDir Path tmp) {
        String wrongSha = "f".repeat(64);
        Manifest manifest = new Manifest("java", List.of(), List.of(
                new Manifest.Library("c:lib1:1", baseUrl + "/lib1.jar", wrongSha)));
        RecordingListener listener = new RecordingListener();

        assertThrows(IOException.class,
                () -> new ManifestConsumer(new LibraryCache(tmp)).resolveAll(manifest, listener));

        assertEquals("finished", listener.events.get(listener.events.size() - 1),
                "finished must be the last event even on failure; got " + listener.events);
    }
}

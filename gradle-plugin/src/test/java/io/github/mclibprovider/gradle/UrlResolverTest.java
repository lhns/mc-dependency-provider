package io.github.mclibprovider.gradle;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GenerateMcLibManifestTask.UrlResolver}'s HEAD-probe behaviour across multiple
 * declared repos — the scenario from ADR-0004's multi-repo case (Sonatype OSS + Central).
 * Single-repo behaviour is already covered by the TestKit test; this one targets the probe path.
 */
class UrlResolverTest {

    private HttpServer hitServer;
    private HttpServer missServer;
    private String hitBase;
    private String missBase;
    private final AtomicInteger hitHeads = new AtomicInteger();
    private final AtomicInteger missHeads = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        hitHeads.set(0);
        missHeads.set(0);
        hitServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hitServer.createContext("/", exchange -> {
            hitHeads.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        hitServer.start();
        hitBase = "http://127.0.0.1:" + hitServer.getAddress().getPort() + "/repo/";

        missServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        missServer.createContext("/", exchange -> {
            missHeads.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        missServer.start();
        missBase = "http://127.0.0.1:" + missServer.getAddress().getPort() + "/repo/";
    }

    @AfterEach
    void stop() {
        hitServer.stop(0);
        missServer.stop(0);
    }

    @Test
    void picksSecondRepoWhenFirstReturns404() {
        // Order: miss first, hit second. Resolver should still land on hit.
        GenerateMcLibManifestTask.UrlResolver resolver =
                new GenerateMcLibManifestTask.UrlResolver(List.of(missBase, hitBase));

        GenerateMcLibManifestTask.ArtifactCoord coord = new GenerateMcLibManifestTask.ArtifactCoord(
                "com.example", "lib", "1.0", "", "jar", "/unused.jar");

        String resolved = resolver.resolve(coord, msg -> {});

        assertTrue(resolved.startsWith(hitBase),
                "expected URL against the hit repo, got: " + resolved);
        assertTrue(resolved.endsWith("com/example/lib/1.0/lib-1.0.jar"),
                "relative path wrong: " + resolved);
        assertEquals(1, missHeads.get(), "miss server should have been HEAD-probed once");
        assertEquals(1, hitHeads.get(), "hit server should have been HEAD-probed once");
    }

    @Test
    void cachesRepoPerCoordinate() {
        GenerateMcLibManifestTask.UrlResolver resolver =
                new GenerateMcLibManifestTask.UrlResolver(List.of(missBase, hitBase));

        GenerateMcLibManifestTask.ArtifactCoord coord = new GenerateMcLibManifestTask.ArtifactCoord(
                "com.example", "lib", "1.0", "", "jar", "/unused.jar");

        resolver.resolve(coord, msg -> {});
        resolver.resolve(coord, msg -> {}); // second call should be cached

        assertEquals(1, missHeads.get(), "cache should prevent re-probing miss server");
        assertEquals(1, hitHeads.get(), "cache should prevent re-probing hit server");
    }

    @Test
    void singleRepoSkipsProbingEntirely() {
        GenerateMcLibManifestTask.UrlResolver resolver =
                new GenerateMcLibManifestTask.UrlResolver(List.of(hitBase));

        GenerateMcLibManifestTask.ArtifactCoord coord = new GenerateMcLibManifestTask.ArtifactCoord(
                "com.example", "lib", "1.0", "", "jar", "/unused.jar");

        String resolved = resolver.resolve(coord, msg -> {});

        assertTrue(resolved.startsWith(hitBase),
                "single-repo case should emit against that repo without probing: " + resolved);
        assertEquals(0, hitHeads.get(), "single-repo case should not HEAD-probe");
    }

    @Test
    void fallsBackToFirstRepoWhenAllProbesFail() {
        // Both servers return 404; resolver should fall back to the first declared repo.
        GenerateMcLibManifestTask.UrlResolver resolver =
                new GenerateMcLibManifestTask.UrlResolver(List.of(missBase, missBase));

        GenerateMcLibManifestTask.ArtifactCoord coord = new GenerateMcLibManifestTask.ArtifactCoord(
                "com.example", "lib", "1.0", "", "jar", "/unused.jar");

        String resolved = resolver.resolve(coord, msg -> {});

        assertTrue(resolved.startsWith(missBase),
                "fallback should be the first declared repo; was: " + resolved);
    }
}

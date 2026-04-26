package de.lhns.mcdp.cli;

import com.sun.net.httpserver.HttpServer;
import de.lhns.mcdp.deps.Manifest;
import de.lhns.mcdp.deps.ManifestIo;
import de.lhns.mcdp.deps.Sha256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PrefetchTest {

    private HttpServer server;
    private String baseUrl;
    private final byte[] body = "prefetched-bytes".getBytes();

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
    void prefetchesLibrariesIntoCache(@TempDir Path tmp) throws IOException {
        Path modJar = buildModJar(tmp.resolve("mod.jar"), manifestString(baseUrl + "/lib.jar", Sha256.hex(body)));
        Path cacheDir = tmp.resolve("cache");

        int code = runCli("--cache", cacheDir.toString(), modJar.toString());
        assertEquals(0, code);
        Path expected = cacheDir.resolve("libs").resolve(Sha256.hex(body) + ".jar");
        assertTrue(Files.isRegularFile(expected), "library jar should be cached at " + expected);
    }

    @Test
    void dryRunDoesNotDownload(@TempDir Path tmp) throws IOException {
        Path modJar = buildModJar(tmp.resolve("mod.jar"), manifestString(baseUrl + "/lib.jar", Sha256.hex(body)));
        Path cacheDir = tmp.resolve("cache");

        int code = runCli("--cache", cacheDir.toString(), "--dry-run", modJar.toString());
        assertEquals(0, code);
        assertFalse(Files.exists(cacheDir.resolve("libs").resolve(Sha256.hex(body) + ".jar")));
    }

    @Test
    void skipsJarsWithoutManifest(@TempDir Path tmp) throws IOException {
        Path plainJar = tmp.resolve("plain.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(plainJar))) {
            zos.putNextEntry(new ZipEntry("pkg/Foo.class"));
            zos.write(new byte[]{0, 1, 2});
            zos.closeEntry();
        }
        int code = runCli("--cache", tmp.resolve("c").toString(), plainJar.toString());
        assertEquals(0, code);
    }

    @Test
    void directoryArgExpandsToJars(@TempDir Path tmp) throws IOException {
        Path modsDir = Files.createDirectories(tmp.resolve("mods"));
        buildModJar(modsDir.resolve("a.jar"), manifestString(baseUrl + "/lib.jar", Sha256.hex(body)));
        Path cacheDir = tmp.resolve("cache");

        int code = runCli("--cache", cacheDir.toString(), modsDir.toString());
        assertEquals(0, code);
        assertTrue(Files.isRegularFile(cacheDir.resolve("libs").resolve(Sha256.hex(body) + ".jar")));
    }

    @Test
    void shaMismatchExitsNonZero(@TempDir Path tmp) throws IOException {
        Path modJar = buildModJar(tmp.resolve("mod.jar"), manifestString(baseUrl + "/lib.jar", "0".repeat(64)));
        int code = runCli("--cache", tmp.resolve("c").toString(), modJar.toString());
        assertEquals(2, code);
    }

    @Test
    void missingArgsExits1() {
        assertEquals(1, runCli());
    }

    private static int runCli(String... args) {
        return Prefetch.run(args,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
    }

    private static String manifestString(String url, String sha) {
        Manifest m = new Manifest("java", List.of(),
                List.of(new Manifest.Library("io.example:lib:1", url, sha)));
        StringWriter sw = new StringWriter();
        try { ManifestIo.write(m, sw); } catch (IOException e) { throw new RuntimeException(e); }
        return sw.toString();
    }

    private static Path buildModJar(Path target, String manifestToml) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(target))) {
            zos.putNextEntry(new ZipEntry("META-INF/mcdepprovider.toml"));
            zos.write(manifestToml.getBytes());
            zos.closeEntry();
        }
        return target;
    }
}

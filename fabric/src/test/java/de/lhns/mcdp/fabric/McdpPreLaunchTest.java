package de.lhns.mcdp.fabric;

import de.lhns.mcdp.api.McdpProvider;
import de.lhns.mcdp.core.ModClassLoader;
import de.lhns.mcdp.deps.LibraryCache;
import de.lhns.mcdp.deps.Manifest;
import de.lhns.mcdp.deps.ManifestConsumer;
import de.lhns.mcdp.deps.ManifestIo;
import de.lhns.mcdp.deps.Sha256;
import net.fabricmc.loader.api.ModContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pre-launch pass, driven through {@link McdpPreLaunch#run} with a proxied
 * {@code FabricLoader} and a {@link ManifestConsumer} over a {@code @TempDir} cache — no network:
 * every library a manifest names is seeded into the cache first, and a cache hit returns before
 * the URL is looked at.
 *
 * <p>Each test uses its own modIds: {@link McdpProvider}'s registries and {@code McdpPreLaunch}'s
 * coordinator are process-global and cannot be reset from here.
 */
class McdpPreLaunchTest {

    /** The bridge impl the bridge-manifest test registers; resolved through the mod's loader. */
    public static final class BridgeImpl {
        public BridgeImpl() {}
    }

    /** Loaders run() built in this test; closed so Windows lets @TempDir delete the jars. */
    private final List<ModClassLoader> built = new ArrayList<>();

    /**
     * Closes the mod loader and the library/promoted loaders above it. A loader with no URLs is
     * left open: that is the coordinator's one library loader for every zero-library mod
     * (keyed by the empty SHA set), shared with every other test in this JVM.
     */
    @AfterEach
    void closeLoaders() throws IOException {
        ClassLoader testLoader = McdpPreLaunchTest.class.getClassLoader();
        for (ModClassLoader l : built) {
            for (ClassLoader cl = l; cl instanceof URLClassLoader u && cl != testLoader; cl = cl.getParent()) {
                if (u.getURLs().length > 0) u.close();
            }
        }
    }

    // --- the two-pass boot ---

    /**
     * Catches: a mod without a manifest being registered (or crashing the pass), the manifest's
     * lang not reaching {@link McdpPreLaunch#langFor}, shared packages dropped when the reduced
     * manifest is rebuilt, and the loader's roots not being the mod's rootPaths.
     */
    @Test
    void registersEveryManifestModWithItsLangRootsAndSharedPackages(@TempDir Path tmp) throws Exception {
        Path scalaRoot = modRoot(tmp, "pl-scala-mod",
                new Manifest("scala", List.of("com.example.api"), List.of()));
        Path kotlinRoot = modRoot(tmp, "pl-kotlin-mod", new Manifest("kotlin", List.of(), List.of()));
        Path plainRoot = Files.createDirectories(tmp.resolve("pl-plain-mod"));

        run(tmp, FabricStubs.mod("pl-scala-mod", scalaRoot),
                FabricStubs.mod("pl-kotlin-mod", kotlinRoot),
                FabricStubs.mod("pl-plain-mod", plainRoot));

        ModClassLoader scala = registered("pl-scala-mod");
        assertEquals(List.of(scalaRoot.toUri().toURL().toString()), urls(scala));
        assertEquals(List.of("com.example.api."), scala.sharedPackages());
        assertEquals("scala", McdpPreLaunch.langFor("pl-scala-mod"));

        registered("pl-kotlin-mod");
        assertEquals("kotlin", McdpPreLaunch.langFor("pl-kotlin-mod"));

        assertNull(McdpProvider.loaderFor("pl-plain-mod"));
        assertEquals("java", McdpPreLaunch.langFor("pl-plain-mod"));
    }

    /** Dev builds: existing devRoots replace the rootPaths; missing ones are dropped. */
    @Test
    void existingDevRootsReplaceTheRootPaths(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("build/classes/java/main"));
        Path missing = tmp.resolve("build/never-created");
        Path root = modRoot(tmp, "pl-dev-mod", new Manifest("java", List.of(), List.of(),
                List.of(classes.toString(), missing.toString())));

        run(tmp, FabricStubs.mod("pl-dev-mod", root));

        assertEquals(List.of(classes.toUri().toURL().toString()), urls(registered("pl-dev-mod")));
    }

    /**
     * A devRoot this OS cannot parse — a path written by a build on another OS — threw
     * InvalidPathException out of {@code Path.of} and crashed boot. It must be skipped like a
     * devRoot that does not exist. NUL is illegal in a path on every JDK filesystem.
     */
    @Test
    void aDevRootThisOsCannotParseIsSkipped(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path root = modRoot(tmp, "pl-bad-devroot-mod", new Manifest("java", List.of(), List.of(),
                List.of("bad\u0000root", classes.toString())));

        run(tmp, FabricStubs.mod("pl-bad-devroot-mod", root));

        assertEquals(List.of(classes.toUri().toURL().toString()), urls(registered("pl-bad-devroot-mod")));
    }

    /**
     * {@code ManifestIo} casts TOML values unchecked, so a string where a {@code [[libraries]]}
     * table belongs is a ClassCastException — which escaped the IOException-only wrap and
     * crashed boot without naming the mod whose manifest is broken.
     */
    @Test
    void aMalformedManifestFailsNamingTheMod(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("pl-malformed-mod/META-INF"));
        Files.writeString(root.resolve("mcdepprovider.toml"),
                "lang = \"java\"\nlibraries = [\"not-a-table\"]\n");

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                run(tmp, FabricStubs.mod("pl-malformed-mod", root.getParent())));

        assertTrue(e.getMessage().startsWith("mcdepprovider:"), e.getMessage());
        assertTrue(e.getMessage().contains("pl-malformed-mod"), e.getMessage());
        assertInstanceOf(ClassCastException.class, e.getCause());
    }

    /**
     * The library case, from a pre-seeded cache. Catches the resolved jars not reaching the
     * mod's loader: the marker resource exists only inside the library jar.
     */
    @Test
    void wiresResolvedLibrariesIntoTheModsLoader(@TempDir Path tmp) throws Exception {
        LibraryCache cache = new LibraryCache(tmp.resolve("cache"));
        Manifest.Library lib = seed(cache, "com.example:pl-lib:1.0", "mcdp-test/pl-lib-marker.txt");
        Path root = modRoot(tmp, "pl-lib-mod", new Manifest("java", List.of(), List.of(lib)));

        McdpPreLaunch.run(FabricStubs.loader(FabricStubs.mod("pl-lib-mod", root)), new ManifestConsumer(cache));

        URL marker = registered("pl-lib-mod").getResource("mcdp-test/pl-lib-marker.txt");
        assertNotNull(marker);
        assertTrue(marker.toString().contains(lib.sha256()), marker.toString());
    }

    /**
     * The point of the two passes: promotion sees every mod's manifest before any loader is
     * built. Two mods pin different scala-library versions; both must end up on the single
     * highest one, from the shared promoted loader, and neither may still see its own older
     * copy. Catches building loaders inside the first loop, not splicing the promoted loader
     * in as the library parent, and not stripping promoted jars from the per-mod list.
     */
    @Test
    void promotesTheHighestStdlibAcrossMods(@TempDir Path tmp) throws Exception {
        LibraryCache cache = new LibraryCache(tmp.resolve("cache"));
        Manifest.Library older = seed(cache, "org.scala-lang:scala-library:2.13.10", "mcdp-test/pl-scala-2.13.10.txt");
        Manifest.Library newer = seed(cache, "org.scala-lang:scala-library:2.13.12", "mcdp-test/pl-scala-2.13.12.txt");
        Path oldRoot = modRoot(tmp, "pl-promo-old-mod", new Manifest("scala", List.of(), List.of(older)));
        Path newRoot = modRoot(tmp, "pl-promo-new-mod", new Manifest("scala", List.of(), List.of(newer)));

        McdpPreLaunch.run(FabricStubs.loader(
                        FabricStubs.mod("pl-promo-old-mod", oldRoot),
                        FabricStubs.mod("pl-promo-new-mod", newRoot)),
                new ManifestConsumer(cache));

        for (String modId : List.of("pl-promo-old-mod", "pl-promo-new-mod")) {
            ModClassLoader loader = registered(modId);
            assertNotNull(loader.getResource("mcdp-test/pl-scala-2.13.12.txt"), modId);
            assertNull(loader.getResource("mcdp-test/pl-scala-2.13.10.txt"), modId);
        }
    }

    /**
     * Catches dropping the {@code mcdp-bridges.toml} registration: the bridge then resolves to
     * "no auto-bridge registered". The impl class is reached through the mod's own loader.
     */
    @Test
    void registersTheModsAutoBridgeManifest(@TempDir Path tmp) throws Exception {
        String mixin = "com.example.fabric.PreLaunchTestMixin";
        Path root = modRoot(tmp, "pl-bridge-mod", new Manifest("java", List.of(), List.of()));
        Files.writeString(root.resolve("META-INF/mcdp-bridges.toml"), "[[bridge]]\n"
                + "mixin = \"" + mixin + "\"\n"
                + "field = \"LOGIC\"\n"
                + "interface = \"com.example.fabric.Logic\"\n"
                + "impl = \"" + BridgeImpl.class.getName() + "\"\n");

        run(tmp, FabricStubs.mod("pl-bridge-mod", root));
        registered("pl-bridge-mod");

        assertEquals(BridgeImpl.class.getName(),
                McdpProvider.resolveAutoBridgeImpl(mixin, "LOGIC").getClass().getName());
    }

    /**
     * Catches run() not registering mixin owners, or registering them against the wrong mod.
     * Two mods, so {@code loadMixinImpl}'s single-mod fallback could not hide a missing owner
     * anyway; the owner map is read directly, which also tells the right mod from the wrong one.
     */
    @Test
    void registersEachModsMixinOwnersAgainstItsOwnLoader(@TempDir Path tmp) throws Exception {
        Path mixinRoot = modRoot(tmp, "pl-mixin-mod", new Manifest("java", List.of(), List.of()));
        Files.writeString(mixinRoot.resolve("fabric.mod.json"),
                "{\"id\":\"pl-mixin-mod\",\"mixins\":[\"pl-mixin-mod.mixins.json\"]}");
        Files.writeString(mixinRoot.resolve("pl-mixin-mod.mixins.json"),
                "{\"package\":\"com.example.plmixin\",\"mixins\":[\"OwnedMixin\"]}");
        Path otherRoot = modRoot(tmp, "pl-mixin-other-mod", new Manifest("java", List.of(), List.of()));

        // The other mod comes first, so it is already registered when the owner's configs are
        // read: an owner recorded against it is a wrong answer here, not a crash.
        run(tmp, FabricStubs.mod("pl-mixin-other-mod", otherRoot),
                FabricStubs.mod("pl-mixin-mod", mixinRoot));

        ModClassLoader owner = registered("pl-mixin-mod");
        registered("pl-mixin-other-mod");
        assertSame(owner, McdpProvider.loaderForMixin("com.example.plmixin.OwnedMixin"));
    }

    // --- selectModPaths ---

    @Test
    void noDevRootsMeansTheRootPaths(@TempDir Path tmp) {
        List<Path> roots = List.of(tmp.resolve("mod.jar"));
        assertEquals(roots, McdpPreLaunch.selectModPaths("sel-a", List.of(), roots));
    }

    /** A shipped jar's devRoots point at the build machine: none exist, so rootPaths win. */
    @Test
    void devRootsThatDoNotExistFallBackToTheRootPaths(@TempDir Path tmp) {
        List<Path> roots = List.of(tmp.resolve("mod.jar"));
        assertEquals(roots, McdpPreLaunch.selectModPaths("sel-b",
                List.of(tmp.resolve("gone-1").toString(), tmp.resolve("gone-2").toString()), roots));
    }

    @Test
    void existingDevRootsWinInOrderAndMissingOnesAreDropped(@TempDir Path tmp) throws Exception {
        Path resources = Files.createDirectories(tmp.resolve("resources"));
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        assertEquals(List.of(resources, classes), McdpPreLaunch.selectModPaths("sel-c",
                List.of(resources.toString(), tmp.resolve("gone").toString(), classes.toString()),
                List.of(tmp.resolve("mod.jar"))));
    }

    @Test
    void anUnparseableDevRootIsSkipped(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        List<Path> roots = List.of(tmp.resolve("mod.jar"));
        assertEquals(List.of(classes),
                McdpPreLaunch.selectModPaths("sel-d", List.of("bad\u0000root", classes.toString()), roots));
        assertEquals(roots, McdpPreLaunch.selectModPaths("sel-d", List.of("bad\u0000root"), roots));
    }

    // --- helpers ---

    private void run(Path tmp, ModContainer... mods) {
        McdpPreLaunch.run(FabricStubs.loader(mods), new ManifestConsumer(new LibraryCache(tmp.resolve("cache"))));
    }

    /** The loader run() registered for {@code modId}; fails if there is none. */
    private ModClassLoader registered(String modId) {
        ModClassLoader loader = McdpProvider.loaderFor(modId);
        assertNotNull(loader, "no loader registered for " + modId);
        built.add(loader);
        return loader;
    }

    private static List<String> urls(URLClassLoader loader) {
        return Arrays.stream(loader.getURLs()).map(URL::toString).toList();
    }

    private static Path modRoot(Path tmp, String modId, Manifest manifest) throws IOException {
        Path root = Files.createDirectories(tmp.resolve(modId));
        Files.createDirectories(root.resolve("META-INF"));
        ManifestIo.write(manifest, root.resolve("META-INF/mcdepprovider.toml"));
        return root;
    }

    /**
     * Store a one-entry jar in the cache under its real SHA-256 and return the library a
     * manifest would declare for it. The URL is never fetched: a cache hit returns first.
     */
    private static Manifest.Library seed(LibraryCache cache, String coords, String markerEntry) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry(markerEntry));
            jar.write(coords.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        byte[] content = bytes.toByteArray();
        String sha = Sha256.hex(content);
        cache.store(sha, content);
        return new Manifest.Library(coords, "https://example.invalid/" + sha + ".jar", sha);
    }
}

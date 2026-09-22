package de.lhns.mcdp.fabric;

import de.lhns.mcdp.api.McdpProvider;
import de.lhns.mcdp.core.ModClassLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link McdpPreLaunch#registerMixinOwnersForFabricMod} over the two root shapes Fabric hands
 * out: a directory in a dev run and the root of a ZIP filesystem for a packaged mod. The returned
 * FQNs are the ones {@link McdpProvider#registerMixinOwner} accepted, so an empty result for an
 * unregistered mod proves they are not just parsed and returned.
 *
 * <p>Each test uses its own modId: {@link McdpProvider}'s registries are process-global and its
 * reset hook is package-private to {@code de.lhns.mcdp.api}.
 */
class FabricMixinOwnersTest {

    private static final String FOO_CONFIG =
            "{\"package\":\"com.example.mixin\",\"mixins\":[\"FooMixin\"],\"client\":[\"BarClient\"]}";
    private static final String BAZ_CONFIG =
            "{\"package\":\"com.example.other\",\"mixins\":[\"BazMixin\"]}";

    /**
     * Production shape. Catches any regression that assumes the default filesystem —
     * {@code resolve} and {@code Files.readString} both have to work on a ZIP-filesystem root.
     */
    @Test
    void registersOwnersFromAPackagedModJar(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("mod.jar");
        writeZip(jar, Map.of(
                "fabric.mod.json", "{\"id\":\"fmo-jar-mod\",\"mixins\":[\"foo.mixins.json\"]}",
                "foo.mixins.json", FOO_CONFIG));

        try (ModClassLoader loader = modLoader("fmo-jar-mod");
             FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
            McdpProvider.registerMod("fmo-jar-mod", loader);

            assertEquals(
                    List.of("com.example.mixin.FooMixin", "com.example.mixin.BarClient"),
                    McdpPreLaunch.registerMixinOwnersForFabricMod(
                            "fmo-jar-mod", List.of(fs.getRootDirectories().iterator().next())));
        }
    }

    /**
     * Dev-run shape, and all three documented {@code mixins} shapes (string entry, object entry
     * with {@code config}). {@code fabric.mod.json} sits in the resources dir while the configs
     * are split across both roots: catches reading only the first root.
     */
    @Test
    void registersOwnersAcrossDevRootDirectories(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path resources = Files.createDirectories(tmp.resolve("resources"));
        Files.writeString(resources.resolve("fabric.mod.json"), "{\"id\":\"fmo-dev-mod\",\"mixins\":["
                + "\"foo.mixins.json\",{\"config\":\"other.mixins.json\",\"environment\":\"client\"}]}");
        Files.writeString(resources.resolve("foo.mixins.json"), FOO_CONFIG);
        Files.writeString(classes.resolve("other.mixins.json"), BAZ_CONFIG);

        try (ModClassLoader loader = modLoader("fmo-dev-mod")) {
            McdpProvider.registerMod("fmo-dev-mod", loader);

            assertEquals(
                    List.of("com.example.mixin.FooMixin",
                            "com.example.mixin.BarClient",
                            "com.example.other.BazMixin"),
                    McdpPreLaunch.registerMixinOwnersForFabricMod("fmo-dev-mod", List.of(classes, resources)));
        }
    }

    /** No {@code fabric.mod.json} under any root, or one without mixins: a silent no-op. */
    @Test
    void modWithoutMixinsRegistersNothing(@TempDir Path tmp) throws Exception {
        Path bare = Files.createDirectories(tmp.resolve("bare"));
        Path plain = Files.createDirectories(tmp.resolve("plain"));
        Files.writeString(plain.resolve("fabric.mod.json"), "{\"id\":\"fmo-plain-mod\"}");

        try (ModClassLoader loader = modLoader("fmo-plain-mod")) {
            McdpProvider.registerMod("fmo-plain-mod", loader);

            assertEquals(List.of(), McdpPreLaunch.registerMixinOwnersForFabricMod("fmo-plain-mod", List.of(bare)));
            assertEquals(List.of(), McdpPreLaunch.registerMixinOwnersForFabricMod("fmo-plain-mod", List.of(plain)));
        }
    }

    /** A malformed {@code fabric.mod.json} must not take boot down: best-effort by contract. */
    @Test
    void malformedFabricModJsonRegistersNothing(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("broken"));
        Files.writeString(root.resolve("fabric.mod.json"), "{\"mixins\": [");

        try (ModClassLoader loader = modLoader("fmo-broken-mod")) {
            McdpProvider.registerMod("fmo-broken-mod", loader);

            assertEquals(List.of(), McdpPreLaunch.registerMixinOwnersForFabricMod("fmo-broken-mod", List.of(root)));
        }
    }

    /** Parsed FQNs for a mod with no registered loader are not reported as registered. */
    @Test
    void staysSilentWhenTheModHasNoRegisteredLoader(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("unregistered"));
        Files.writeString(root.resolve("fabric.mod.json"), "{\"mixins\":\"foo.mixins.json\"}");
        Files.writeString(root.resolve("foo.mixins.json"), FOO_CONFIG);

        assertEquals(List.of(),
                McdpPreLaunch.registerMixinOwnersForFabricMod("fmo-never-registered-mod", List.of(root)));
    }

    // --- helpers ---

    private static ModClassLoader modLoader(String modId) {
        return new ModClassLoader(modId, new URL[0], FabricMixinOwnersTest.class.getClassLoader(), List.of());
    }

    private static void writeZip(Path zip, Map<String, String> entries) throws Exception {
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }
}

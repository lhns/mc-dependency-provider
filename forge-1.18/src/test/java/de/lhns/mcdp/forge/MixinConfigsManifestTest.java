package de.lhns.mcdp.forge;

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
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forge's {@code mods.toml} has no {@code [[mixins]]} block, so
 * {@link McdpLanguageProvider#registerMixinOwnersFromModFile} reads Mixin's own
 * {@code MixinConfigs} jar-manifest attribute instead. Everything here drives that method
 * through the two {@code Path} shapes {@code IModFile.findResource} returns — a directory in a
 * dev run, a ZIP-filesystem path in production — so no forgespi stub is needed.
 *
 * <p>Each test uses its own modId: {@link McdpProvider}'s registries are process-global and its
 * reset hook is package-private to {@code de.lhns.mcdp.api}.
 */
class MixinConfigsManifestTest {

    private static final String FOO_CONFIG =
            "{\"package\":\"com.example.mixin\",\"mixins\":[\"FooMixin\"],\"client\":[\"BarClient\"]}";
    private static final String BAZ_CONFIG =
            "{\"package\":\"com.example.other\",\"mixins\":[\"BazMixin\"]}";

    /**
     * Production shape. Catches: reading the wrong attribute name, and any regression that
     * assumes the default filesystem — {@code Files.readString} and {@code java.util.jar.Manifest}
     * both have to work on a jar-filesystem path, which is all {@code findResource} yields for a
     * packaged mod.
     */
    @Test
    void registersOwnersFromAPackagedModJar(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("mod.jar");
        writeJar(jar, "foo.mixins.json", "foo.mixins.json", FOO_CONFIG);

        try (ModClassLoader loader = modLoader("jar-mod");
             FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
            McdpProvider.registerMod("jar-mod", loader);

            assertEquals(
                    List.of("com.example.mixin.FooMixin", "com.example.mixin.BarClient"),
                    McdpLanguageProvider.registerMixinOwnersFromModFile(
                            "jar-mod", name -> fs.getPath(name)));
        }
    }

    /**
     * Catches a single-config shortcut that ignores everything after the first comma — Mixin
     * itself splits the attribute that way, so a two-config mod must map both.
     */
    @Test
    void splitsTheAttributeOnCommasAndTrims(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("exploded"));
        writeManifest(root, "foo.mixins.json, other.mixins.json");
        Files.writeString(root.resolve("foo.mixins.json"), FOO_CONFIG);
        Files.writeString(root.resolve("other.mixins.json"), BAZ_CONFIG);

        try (ModClassLoader loader = modLoader("two-config-mod")) {
            McdpProvider.registerMod("two-config-mod", loader);

            assertEquals(
                    List.of("com.example.mixin.FooMixin",
                            "com.example.mixin.BarClient",
                            "com.example.other.BazMixin"),
                    McdpLanguageProvider.registerMixinOwnersFromModFile(
                            "two-config-mod", name -> root.resolve(name)));
        }
    }

    /**
     * The dev-run shape: an exploded source-set output has no {@code META-INF/MANIFEST.MF} at
     * all (ForgeGradle passes {@code --mixin.config} instead, which names no mod). Catches a
     * regression that lets the missing file surface as an exception — {@code findResource} hands
     * back a path for resources no root holds, so this arrives as a NoSuchFileException rather
     * than as a null, and it has to stay a silent no-op either way.
     */
    @Test
    void devRunWithoutAJarManifestRegistersNothing(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("classes"));
        Files.writeString(root.resolve("foo.mixins.json"), FOO_CONFIG);

        try (ModClassLoader loader = modLoader("dev-mod")) {
            McdpProvider.registerMod("dev-mod", loader);

            assertEquals(List.of(),
                    McdpLanguageProvider.registerMixinOwnersFromModFile(
                            "dev-mod", name -> root.resolve(name)));
        }
    }

    /** A mod with a manifest but no MixinConfigs attribute — i.e. almost every mod. */
    @Test
    void modWithoutMixinsRegistersNothing(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("classes"));
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/MANIFEST.MF"),
                "Manifest-Version: 1.0\nFMLModType: MOD\n\n");

        try (ModClassLoader loader = modLoader("plain-mod")) {
            McdpProvider.registerMod("plain-mod", loader);

            assertEquals(List.of(),
                    McdpLanguageProvider.registerMixinOwnersFromModFile(
                            "plain-mod", name -> root.resolve(name)));
        }
    }

    /**
     * Proves the FQNs actually travel through {@link McdpProvider#registerMixinOwner} rather
     * than being parsed and dropped: that call is the only thing that can fail for an
     * unregistered modId, and the scanner's best-effort contract turns the failure into an empty
     * result instead of a boot crash. A mutation that returned the parsed FQNs directly would
     * report two here.
     */
    @Test
    void staysSilentWhenTheModHasNoRegisteredLoader(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("exploded"));
        writeManifest(root, "foo.mixins.json");
        Files.writeString(root.resolve("foo.mixins.json"), FOO_CONFIG);

        assertEquals(List.of(),
                McdpLanguageProvider.registerMixinOwnersFromModFile(
                        "never-registered-mod", name -> root.resolve(name)));
    }

    /** A manifest naming a config the mod file doesn't carry must not take the others down. */
    @Test
    void oneMissingConfigDoesNotCancelTheRest(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("exploded"));
        writeManifest(root, "gone.mixins.json,foo.mixins.json");
        Files.writeString(root.resolve("foo.mixins.json"), FOO_CONFIG);

        try (ModClassLoader loader = modLoader("partial-mod")) {
            McdpProvider.registerMod("partial-mod", loader);

            List<String> registered = McdpLanguageProvider.registerMixinOwnersFromModFile(
                    "partial-mod", name -> root.resolve(name));
            assertTrue(registered.contains("com.example.mixin.FooMixin"), registered.toString());
        }
    }

    // --- helpers ---

    private static ModClassLoader modLoader(String modId) {
        return new ModClassLoader(modId, new URL[0],
                MixinConfigsManifestTest.class.getClassLoader(), List.of());
    }

    private static void writeManifest(Path root, String mixinConfigs) throws Exception {
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/MANIFEST.MF"),
                "Manifest-Version: 1.0\nMixinConfigs: " + mixinConfigs + "\n\n",
                StandardCharsets.UTF_8);
    }

    private static void writeJar(Path jar, String mixinConfigs, String entryName, String content)
            throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("MixinConfigs", mixinConfigs);
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(out, manifest)) {
            jos.putNextEntry(new JarEntry(entryName));
            jos.write(content.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }
}

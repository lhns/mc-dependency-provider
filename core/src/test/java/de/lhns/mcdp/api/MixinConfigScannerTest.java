package de.lhns.mcdp.api;

import de.lhns.mcdp.core.MixinConfigScanner;
import de.lhns.mcdp.core.ModClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lives under the {@code api} package so it can call the package-private
 * {@link McdpProvider#resetForTests()} hook — the class under test ({@code MixinConfigScanner})
 * is in {@code core} but only depends on the public {@code registerMixinOwner} API, so the
 * package mismatch is acceptable here.
 */
class MixinConfigScannerTest {

    @BeforeEach
    void reset() { McdpProvider.resetForTests(); }

    @AfterEach
    void cleanup() { McdpProvider.resetForTests(); }

    @Test
    void extractsFqnsFromCommonClientServerLists() {
        String config = """
                {
                  "package": "com.example.mixin",
                  "mixins":  ["FooMixin"],
                  "client":  ["BarClient"],
                  "server":  ["BazServer"]
                }""";
        List<String> fqns = MixinConfigScanner.extractFqnsFromConfig(config);
        assertEquals(
                List.of("com.example.mixin.FooMixin",
                        "com.example.mixin.BarClient",
                        "com.example.mixin.BazServer"),
                fqns);
    }

    @Test
    void ignoresMissingOrEmptyFields() {
        assertEquals(List.of(), MixinConfigScanner.extractFqnsFromConfig("{\"package\":\"p\"}"));
        assertEquals(List.of(), MixinConfigScanner.extractFqnsFromConfig("{\"package\":\"\",\"mixins\":[\"X\"]}"));
        assertEquals(List.of(), MixinConfigScanner.extractFqnsFromConfig("{\"mixins\":[\"X\"]}"));
    }

    @Test
    void parsesFabricMixinsFieldInAllThreeShapes() {
        assertEquals(List.of("a.mixins.json"),
                MixinConfigScanner.parseFabricMixinConfigs("{\"mixins\":\"a.mixins.json\"}"));
        assertEquals(List.of("a.mixins.json", "b.mixins.json"),
                MixinConfigScanner.parseFabricMixinConfigs("{\"mixins\":[\"a.mixins.json\",\"b.mixins.json\"]}"));
        assertEquals(List.of("a.mixins.json"),
                MixinConfigScanner.parseFabricMixinConfigs(
                        "{\"mixins\":[{\"config\":\"a.mixins.json\",\"environment\":\"client\"}]}"));
    }

    @Test
    void registersOwnersFromFilesOnDisk(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("mod-root"));
        Files.writeString(root.resolve("foo.mixins.json"), """
                {"package":"com.example.mixin","mixins":["FooMixin"],"client":["ClientMixin"]}""");

        try (var loader = new ModClassLoader("m1", new URL[0], getClass().getClassLoader(), List.of())) {
            McdpProvider.registerMod("m1", loader);
            List<String> registered = MixinConfigScanner.registerMixinOwnersFromConfigs(
                    "m1", List.of(root), List.of("foo.mixins.json"));

            assertEquals(
                    List.of("com.example.mixin.FooMixin", "com.example.mixin.ClientMixin"),
                    registered);
        }
    }

    @Test
    void bestEffortSilentOnMissingOrMalformedConfig(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("mod-root"));
        Files.writeString(root.resolve("bad.mixins.json"), "this is not json");

        try (var loader = new ModClassLoader("m1", new URL[0], getClass().getClassLoader(), List.of())) {
            McdpProvider.registerMod("m1", loader);
            List<String> registered = MixinConfigScanner.registerMixinOwnersFromConfigs(
                    "m1", List.of(root),
                    List.of("missing.mixins.json", "bad.mixins.json"));
            assertEquals(List.of(), registered);
        }
    }
}

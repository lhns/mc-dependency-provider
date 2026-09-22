package de.lhns.mcdp.fabric;

import de.lhns.mcdp.api.McdpProvider;
import de.lhns.mcdp.core.ModClassLoader;
import de.lhns.mcdp.deps.LibraryCache;
import de.lhns.mcdp.deps.ManifestConsumer;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McdpLanguageAdapter#create} against a {@link ModClassLoader} registered through the
 * public {@link McdpProvider#registerMod} and a proxied {@link ModContainer}.
 *
 * <p>Each test uses its own modId: {@link McdpProvider}'s registries are process-global and its
 * reset hook is package-private to {@code de.lhns.mcdp.api}.
 */
class McdpLanguageAdapterTest {

    /** The entrypoint. Public with a public no-arg ctor, as a real Fabric entrypoint is. */
    public static final class Entry implements Runnable {
        public Entry() {}

        @Override
        public void run() {}
    }

    /** No constructor the Java adapter can fill from Fabric's empty context bag. */
    public static final class NoUsableCtor implements Runnable {
        public NoUsableCtor(String required) {}

        @Override
        public void run() {}
    }

    /**
     * Catches loading the entrypoint through any loader but the mod's own. The mod loader holds
     * its own copy of {@link Entry}'s bytes, and it is child-first for this package, so only a
     * lookup through it yields a class that is not the test's {@code Entry}.
     */
    @Test
    void constructsTheEntrypointThroughTheModsLoader(@TempDir Path tmp) throws Exception {
        String entryResource = Entry.class.getName().replace('.', '/') + ".class";
        Path copy = tmp.resolve(entryResource);
        Files.createDirectories(copy.getParent());
        try (InputStream in = McdpLanguageAdapterTest.class.getClassLoader()
                .getResourceAsStream(entryResource)) {
            Files.write(copy, in.readAllBytes());
        }

        try (ModClassLoader loader = new ModClassLoader("adapter-success-mod",
                new URL[]{tmp.toUri().toURL()}, McdpLanguageAdapterTest.class.getClassLoader(), List.of())) {
            McdpProvider.registerMod("adapter-success-mod", loader);

            Runnable instance = new McdpLanguageAdapter().create(
                    FabricStubs.mod("adapter-success-mod"), Entry.class.getName(), Runnable.class);

            assertEquals(Entry.class.getName(), instance.getClass().getName());
            assertSame(loader, instance.getClass().getClassLoader());
            assertNotSame(Entry.class, instance.getClass());
        }
    }

    /** Pre-launch never ran for this mod (or it has no manifest): a named, wrapped failure. */
    @Test
    void failsNamingTheModWhenNoLoaderIsRegistered() {
        LanguageAdapterException e = assertThrows(LanguageAdapterException.class, () ->
                new McdpLanguageAdapter().create(
                        FabricStubs.mod("adapter-never-registered-mod"), Entry.class.getName(), Runnable.class));

        assertTrue(e.getMessage().startsWith("mcdepprovider:"), e.getMessage());
        assertTrue(e.getMessage().contains("adapter-never-registered-mod"), e.getMessage());
    }

    @Test
    void wrapsAMissingEntrypointClass() throws Exception {
        try (ModClassLoader loader = emptyLoader("adapter-missing-class-mod")) {
            McdpProvider.registerMod("adapter-missing-class-mod", loader);

            LanguageAdapterException e = assertThrows(LanguageAdapterException.class, () ->
                    new McdpLanguageAdapter().create(
                            FabricStubs.mod("adapter-missing-class-mod"), "com.example.DoesNotExist", Runnable.class));

            assertTrue(e.getMessage().startsWith("mcdepprovider:"), e.getMessage());
            assertTrue(e.getMessage().contains("com.example.DoesNotExist"), e.getMessage());
            assertInstanceOf(ClassNotFoundException.class, e.getCause());
        }
    }

    /**
     * Catches dropping the {@code type.isInstance} check: without it the method returns an
     * object of the wrong type through an unchecked cast, and Fabric fails later with a
     * ClassCastException that names neither the entrypoint nor the mod.
     */
    @Test
    void rejectsAnEntrypointOfTheWrongType() throws Exception {
        try (ModClassLoader loader = emptyLoader("adapter-wrong-type-mod")) {
            McdpProvider.registerMod("adapter-wrong-type-mod", loader);

            LanguageAdapterException e = assertThrows(LanguageAdapterException.class, () ->
                    new McdpLanguageAdapter().create(
                            FabricStubs.mod("adapter-wrong-type-mod"), Entry.class.getName(), Comparable.class));

            assertTrue(e.getMessage().startsWith("mcdepprovider:"), e.getMessage());
            assertTrue(e.getMessage().contains(Comparable.class.getName()), e.getMessage());
        }
    }

    @Test
    void wrapsAConstructionFailure() throws Exception {
        try (ModClassLoader loader = emptyLoader("adapter-no-ctor-mod")) {
            McdpProvider.registerMod("adapter-no-ctor-mod", loader);

            LanguageAdapterException e = assertThrows(LanguageAdapterException.class, () ->
                    new McdpLanguageAdapter().create(
                            FabricStubs.mod("adapter-no-ctor-mod"), NoUsableCtor.class.getName(), Runnable.class));

            assertTrue(e.getMessage().startsWith("mcdepprovider:"), e.getMessage());
            assertInstanceOf(NoSuchMethodException.class, e.getCause());
        }
    }

    /**
     * An unknown manifest {@code lang} used to escape as a bare IllegalArgumentException from
     * {@code EntrypointAdapter.forLang}; Fabric expects a LanguageAdapterException, and the
     * crash named neither the mod nor mcdepprovider. The lang reaches the adapter the way it does
     * in production: through pre-launch reading the mod's manifest. The entrypoint is a loadable
     * class so the call gets past {@code Class.forName} to the lang lookup.
     */
    @Test
    void wrapsAnUnsupportedLangNamingTheMod(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("cobol-mod"));
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/mcdepprovider.toml"), "lang = \"cobol\"\n");
        ModContainer mod = FabricStubs.mod("adapter-cobol-mod", root);

        McdpPreLaunch.run(FabricStubs.loader(mod),
                new ManifestConsumer(new LibraryCache(tmp.resolve("cache"))));

        LanguageAdapterException e = assertThrows(LanguageAdapterException.class, () ->
                new McdpLanguageAdapter().create(mod, Entry.class.getName(), Runnable.class));

        assertTrue(e.getMessage().startsWith("mcdepprovider:"), e.getMessage());
        assertTrue(e.getMessage().contains("adapter-cobol-mod"), e.getMessage());
        assertTrue(e.getMessage().contains("cobol"), e.getMessage());
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }

    private static ModClassLoader emptyLoader(String modId) {
        return new ModClassLoader(modId, new URL[0], McdpLanguageAdapterTest.class.getClassLoader(), List.of());
    }
}

package de.lhns.mcdp.neoforge;

import de.lhns.mcdp.api.McdpProvider;
import de.lhns.mcdp.core.ModClassLoader;
import de.lhns.mcdp.deps.Manifest;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageLoader;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static de.lhns.mcdp.neoforge.NeoForgeTestSupport.call;
import static de.lhns.mcdp.neoforge.NeoForgeTestSupport.read;
import static de.lhns.mcdp.neoforge.NeoForgeTestSupport.stub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code McdpLanguageLoader} of the FML-10 tree (1.21.11, and the source neoforge-26 compiles),
 * on loader 10.0.36. FML 10 hands out mod-jar entries through {@code JarContents} rather than as
 * {@code Path}s, so everything here runs over FML's own {@code JarContents.ofPath} — a
 * {@code FolderJarContents} for a directory, a {@code JarFileContents} for a jar — rather than
 * over a fake.
 *
 * <p>Every mod id is unique to its test: the registries are process-global. Seams are reached
 * through {@link NeoForgeTestSupport#call} — see there for why.
 */
class McdpLanguageLoaderFml10Test {

    private static final String MANIFEST_PATH = "META-INF/mcdepprovider.toml";
    private static final String BRIDGES_PATH = "META-INF/mcdp-bridges.toml";
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    /** Everything a test opened, closed before {@code @TempDir} is deleted (Windows locks). */
    private final List<AutoCloseable> opened = new ArrayList<>();

    @AfterEach
    void closeOpened() throws Exception {
        for (AutoCloseable c : opened) c.close();
    }

    /** What the fix below relies on: FML 10 throws here where FML 4 returned null. */
    @Test
    void loadingModListThrowsRatherThanReturningNullBeforeFmlBuiltIt() {
        assertThrows(IllegalStateException.class, LoadingModList::get);
    }

    /**
     * McdpProvider re-runs its populator after a throw, never after a normal return. The
     * populator guarded {@code LoadingModList.get()} with a {@code null} check that cannot fire
     * on FML 10 (see above), so "not built yet" surfaced as FML's own bare exception; it is now
     * recognised and reported as mcdp's, and stays retryable.
     */
    @Test
    void lazyPopulatorStaysRetryableWhileLoadingModListIsNotBuilt() throws Exception {
        Class.forName(McdpLanguageLoader.class.getName(), true, getClass().getClassLoader());
        String mixin = "mcdp.test.PopulatorProbe" + Long.toHexString(System.nanoTime());

        for (int attempt = 1; attempt <= 2; attempt++) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> McdpProvider.resolveAutoBridgeImpl(mixin, "LOGIC"));
            assertEquals("mcdepprovider: lazy bridge-registry populator failed", ex.getMessage(),
                    "attempt " + attempt + ": the populator must run, and fail, on every miss");
            assertTrue(ex.getCause().getMessage().startsWith("mcdepprovider: LoadingModList"),
                    String.valueOf(ex.getCause()));
        }
    }

    /**
     * One unusable manifest must cost only its own mod its promotion; it used to switch
     * promotion off for every mod (a {@code catch (Throwable)} around the whole walk).
     */
    @Test
    void promotionSelectionLeavesAnUnusableManifestOut(@TempDir Path tmp) throws Exception {
        IModInfo newer = folderMod(tmp, "fml10_promo_newer",
                manifest("org.jetbrains.kotlin:kotlin-stdlib:2.1.0", SHA_A));
        IModInfo older = folderMod(tmp, "fml10_promo_older",
                manifest("org.jetbrains.kotlin:kotlin-stdlib:1.9.24", SHA_B));
        IModInfo shortSha = folderMod(tmp, "fml10_promo_short_sha",
                manifest("org.jetbrains.kotlin:kotlin-stdlib:2.2.0", "abc"));
        IModInfo noVersion = folderMod(tmp, "fml10_promo_no_version",
                manifest("org.jetbrains.kotlin:kotlin-stdlib", SHA_B));
        IModInfo corruptToml = folderMod(tmp, "fml10_promo_corrupt", "lang = \n[[[");
        IModInfo notOurs = modInfo("fml10_promo_javafml", "javafml", stub(IModFile.class, Map.of(
                "getContents", a -> {
                    throw new AssertionError("read the manifest of a mod mcdp does not load");
                })), null);

        Object selected = call(McdpLanguageLoader.class, "selectPromotions", Map.class,
                new Class<?>[] {List.class},
                List.of(shortSha, corruptToml, newer, noVersion, notOurs, older));

        assertEquals(Map.of("org.jetbrains.kotlin:kotlin-stdlib", new Manifest.Library(
                        "org.jetbrains.kotlin:kotlin-stdlib:2.1.0",
                        "https://example.invalid/lib.jar", SHA_A)),
                selected);
    }

    /**
     * The per-mod registration lock, with the first caller parked inside
     * {@code IModFile.getContents()} — the first mod-jar access in {@code registerNow}. The second
     * caller must block on the lock instead of opening the jar itself.
     */
    @Test
    void ensureRegisteredRunsOnceUnderContention(@TempDir Path tmp) throws Exception {
        String modId = "fml10_race_" + Long.toHexString(System.nanoTime());
        Path root = Files.createDirectories(tmp.resolve(modId));
        writeText(root, MANIFEST_PATH, "lang = \"java\"\n");
        JarContents contents = open(root);

        CountDownLatch inGetContents = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger contentsCalls = new AtomicInteger();
        IModFile file = stub(IModFile.class, Map.of(
                "getFilePath", a -> root,
                "getContents", a -> {
                    if (contentsCalls.incrementAndGet() == 1) {
                        inGetContents.countDown();
                        awaitQuietly(release);
                    }
                    return contents;
                }));
        IModInfo info = modInfo(modId, "mcdepprovider", file, null);

        AtomicReference<Object> first = new AtomicReference<>();
        AtomicReference<Object> second = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t1 = new Thread(() -> register(info, first, failure), "fml10-race-1");
        Thread t2 = new Thread(() -> register(info, second, failure), "fml10-race-2");

        t1.start();
        assertTrue(inGetContents.await(30, TimeUnit.SECONDS), "first caller never opened the jar");
        t2.start();
        spinUntil(() -> t2.getState() == Thread.State.BLOCKED || !t2.isAlive());
        Thread.State secondWhileFirstHeld = t2.getState();
        release.countDown();
        t1.join(TimeUnit.SECONDS.toMillis(30));
        t2.join(TimeUnit.SECONDS.toMillis(30));

        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(Thread.State.BLOCKED, secondWhileFirstHeld,
                "the second caller must wait on the per-mod lock, not run the registration");
        assertEquals(1, contentsCalls.get(), "registration ran more than once");
        assertSame(first.get(), second.get(), "both callers must get the same Registered");
        assertSame(read(first.get(), "loader", ModClassLoader.class), McdpProvider.loaderFor(modId),
                "McdpProvider must hold the loader the cache returned");
    }

    /**
     * FML 10 has no {@code Path} for a jar entry, so the bridge manifest travels as text through
     * {@code readOptionalText} and core's content overload. Proven end to end: the registered
     * bridge resolves to an instance of its impl through the mod's own loader.
     */
    @Test
    void registrationFeedsTheBridgeTomlThroughTheContentOverload(@TempDir Path tmp)
            throws Exception {
        String modId = "fml10_bridges_" + Long.toHexString(System.nanoTime());
        String mixin = "com.example.fml10." + modId + ".SomeMixin";
        Path root = Files.createDirectories(tmp.resolve(modId));
        writeText(root, MANIFEST_PATH, "lang = \"java\"\n");
        writeText(root, BRIDGES_PATH, "[[bridge]]\n"
                + "mixin = \"" + mixin + "\"\n"
                + "field = \"LOGIC\"\n"
                + "interface = \"com.example.fml10.Bridge\"\n"
                + "impl = \"" + BridgeImpl.class.getName() + "\"\n");
        IModInfo info = modInfo(modId, "mcdepprovider", jarModFile(root, open(root)), null);

        Object registered = call(McdpLanguageLoader.class, "ensureRegistered", registeredType(),
                new Class<?>[] {IModInfo.class}, info);

        assertSame(read(registered, "loader", ModClassLoader.class), McdpProvider.loaderFor(modId));
        assertInstanceOf(BridgeImpl.class, McdpProvider.resolveAutoBridgeImpl(mixin, "LOGIC"));
    }

    /** {@code [[mixins]]} configs read out of a directory's {@code JarContents}. */
    @Test
    void mixinOwnersComeFromTheMixinsBlockInAFolder(@TempDir Path tmp) throws Exception {
        String modId = "fml10_mixin_dir_" + Long.toHexString(System.nanoTime());
        writeText(tmp, "foo.mixins.json",
                "{\"package\":\"com.example.fml10mixin\",\"mixins\":[\"FooMixin\"],"
                        + "\"client\":[\"BarClient\"]}");
        McdpProvider.registerMod(modId, emptyLoader(modId));
        IModInfo info = modInfo(modId, "mcdepprovider", null,
                mixinsBlock("foo.mixins.json", "absent.mixins.json"));

        assertEquals(List.of("com.example.fml10mixin.FooMixin", "com.example.fml10mixin.BarClient"),
                registerMixinOwners(info, open(tmp), modId));
    }

    /** The same out of a packaged jar's {@code JarContents}. */
    @Test
    void mixinOwnersComeFromTheMixinsBlockInAJar(@TempDir Path tmp) throws Exception {
        String modId = "fml10_mixin_jar_" + Long.toHexString(System.nanoTime());
        Path jar = writeZip(tmp.resolve("mod.jar"), Map.of("nested/bar.mixins.json",
                "{\"package\":\"com.example.fml10jar\",\"server\":[\"BazServer\"]}"));
        McdpProvider.registerMod(modId, emptyLoader(modId));
        IModInfo info = modInfo(modId, "mcdepprovider", null, mixinsBlock("nested/bar.mixins.json"));

        assertEquals(List.of("com.example.fml10jar.BazServer"),
                registerMixinOwners(info, open(jar), modId));
    }

    @Test
    void readOptionalTextReadsFoldersAndJarsAndAnswersNullForAbsentEntries(@TempDir Path tmp)
            throws Exception {
        Path folder = Files.createDirectories(tmp.resolve("folder"));
        writeText(folder, "META-INF/some.txt", "from a folder");
        Path jar = writeZip(tmp.resolve("some.jar"), Map.of("META-INF/some.txt", "from a jar"));

        JarContents folderContents = open(folder);
        JarContents jarContents = open(jar);
        assertEquals("from a folder", readOptionalText(folderContents, "META-INF/some.txt"));
        assertEquals("from a jar", readOptionalText(jarContents, "META-INF/some.txt"));
        assertNull(readOptionalText(folderContents, "META-INF/absent.txt"));
        assertNull(readOptionalText(jarContents, "META-INF/absent.txt"));
    }

    @Test
    void readManifestReadsFromJarContents(@TempDir Path tmp) throws Exception {
        Path jar = writeZip(tmp.resolve("with-manifest.jar"),
                Map.of(MANIFEST_PATH, "lang = \"kotlin\"\nshared_packages = [\"com.example.api\"]\n"));

        Manifest m = readManifest(open(jar), jar, "fml10_manifest_jar");

        assertEquals("kotlin", m.lang());
        assertEquals(List.of("com.example.api"), m.sharedPackages());
    }

    /** {@code JarContents} does not see the entry, but the jar on disk has it. */
    @Test
    void readManifestFallsBackToTheJarOnDisk(@TempDir Path tmp) throws Exception {
        Path jar = writeZip(tmp.resolve("fallback.jar"), Map.of(MANIFEST_PATH, "lang = \"scala\"\n"));
        JarContents seesNothing = open(Files.createDirectories(tmp.resolve("empty")));

        assertEquals("scala", readManifest(seesNothing, jar, "fml10_manifest_fallback").lang());
        assertEquals("scala", readManifest(null, jar, "fml10_manifest_null_contents").lang());
    }

    @Test
    void readManifestFailsNamingTheModWhenThereIsNone(@TempDir Path tmp) throws Exception {
        Path folder = Files.createDirectories(tmp.resolve("no-manifest"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> readManifest(open(folder), folder, "fml10_manifest_missing"));
        assertTrue(ex.getMessage().startsWith("mcdepprovider: fml10_manifest_missing "),
                ex.getMessage());
    }

    @Test
    void entryClassIsTheModAnnotationCarryingThisModsId() {
        ModFileScanData scan = new ModFileScanData();
        scan.getAnnotations().add(modAnnotation(OtherModEntry.class, "fml10_entry_other"));
        scan.getAnnotations().add(modAnnotation(ThisModEntry.class, "fml10_entry_this"));

        assertSame(ThisModEntry.class, loadEntryClass(scan, "fml10_entry_this"));
    }

    @Test
    void entryClassLookupFailsRatherThanTakingAnotherModsClass() {
        ModFileScanData scan = new ModFileScanData();
        scan.getAnnotations().add(modAnnotation(OtherModEntry.class, "fml10_entry_other"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> loadEntryClass(scan, "fml10_entry_missing"));
        assertTrue(ex.getMessage().contains("@Mod(\"fml10_entry_missing\")"), ex.getMessage());
    }

    // --- fixtures ----------------------------------------------------------------------------

    public static final class ThisModEntry {}

    public static final class OtherModEntry {}

    /** Bridge impl the content-overload test resolves; needs only a no-arg constructor. */
    public static final class BridgeImpl {}

    // --- helpers -----------------------------------------------------------------------------

    private JarContents open(Path path) throws IOException {
        JarContents contents = JarContents.ofPath(path);
        opened.add(contents);
        return contents;
    }

    private static List<?> registerMixinOwners(IModInfo info, JarContents contents, String modId) {
        return (List<?>) call(McdpLanguageLoader.class, "registerMixinOwnersForNeoForgeMod", List.class,
                new Class<?>[] {IModInfo.class, JarContents.class, String.class},
                info, contents, modId);
    }

    private static String readOptionalText(JarContents contents, String name) {
        return (String) call(McdpLanguageLoader.class, "readOptionalText", String.class,
                new Class<?>[] {JarContents.class, String.class}, contents, name);
    }

    private static Manifest readManifest(JarContents contents, Path modFile, String modId) {
        return (Manifest) call(McdpLanguageLoader.class, "readManifest", Manifest.class,
                new Class<?>[] {JarContents.class, Path.class, String.class},
                contents, modFile, modId);
    }

    private static Class<?> loadEntryClass(ModFileScanData scan, String modId) {
        return (Class<?>) call(McdpLanguageLoader.class, "loadEntryClass", Class.class,
                new Class<?>[] {ModFileScanData.class, ModClassLoader.class, String.class},
                scan, emptyLoader(modId), modId);
    }

    /** {@code McdpLanguageLoader.Registered}, a private record. */
    private static Class<?> registeredType() {
        try {
            return Class.forName(McdpLanguageLoader.class.getName() + "$Registered");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static void register(IModInfo info, AtomicReference<Object> out,
                                 AtomicReference<Throwable> failure) {
        try {
            out.set(call(McdpLanguageLoader.class, "ensureRegistered", registeredType(),
                    new Class<?>[] {IModInfo.class}, info));
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private IModInfo folderMod(Path tmp, String modId, String manifestToml) throws Exception {
        Path root = Files.createDirectories(tmp.resolve(modId));
        writeText(root, MANIFEST_PATH, manifestToml);
        return modInfo(modId, "mcdepprovider", jarModFile(root, open(root)), null);
    }

    private static IModFile jarModFile(Path path, JarContents contents) {
        return stub(IModFile.class, Map.of(
                "getFilePath", a -> path,
                "getContents", a -> contents));
    }

    private static String manifest(String coords, String sha) {
        return "lang = \"java\"\n\n[[libraries]]\n"
                + "coords = \"" + coords + "\"\n"
                + "url    = \"https://example.invalid/lib.jar\"\n"
                + "sha256 = \"" + sha + "\"\n";
    }

    private static IModInfo modInfo(String modId, String loaderName, IModFile file,
                                    IConfigurable fileConfig) {
        IModLanguageLoader loader = stub(IModLanguageLoader.class, Map.of(
                "name", a -> loaderName));
        Map<String, Function<Object[], Object>> fileInfoAnswers = new HashMap<>();
        fileInfoAnswers.put("getFile", a -> file);
        fileInfoAnswers.put("getConfig", a -> fileConfig);
        IModFileInfo fileInfo = stub(IModFileInfo.class, fileInfoAnswers);
        return stub(IModInfo.class, Map.of(
                "getModId", a -> modId,
                "getLoader", a -> loader,
                "getOwningFile", a -> fileInfo));
    }

    private static IConfigurable mixinsBlock(String... configs) {
        List<IConfigurable> entries = new ArrayList<>();
        for (String cfg : configs) {
            entries.add(stub(IConfigurable.class, Map.of(
                    "getConfigElement", a -> List.of((String[]) a[0]).equals(List.of("config"))
                            ? Optional.of(cfg) : Optional.empty())));
        }
        return stub(IConfigurable.class, Map.of(
                "getConfigList", a -> List.of((String[]) a[0]).equals(List.of("mixins"))
                        ? entries : List.of()));
    }

    private static ModFileScanData.AnnotationData modAnnotation(Class<?> clazz, String modId) {
        return new ModFileScanData.AnnotationData(Type.getType(Mod.class), ElementType.TYPE,
                Type.getType(clazz), null, Map.of("value", modId));
    }

    private static ModClassLoader emptyLoader(String modId) {
        return new ModClassLoader(modId, new URL[0],
                McdpLanguageLoaderFml10Test.class.getClassLoader(), List.of());
    }

    private static void writeText(Path root, String name, String text) throws Exception {
        Path p = root.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    private static Path writeZip(Path zip, Map<String, String> entries) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) throw new AssertionError("never released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Busy-wait on a thread-state condition; no sleeps, bounded so a hang fails the test. */
    private static void spinUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            assertFalse(System.nanoTime() > deadline, "condition not reached within 30 s");
            Thread.onSpinWait();
        }
    }

    /**
     * Once the body has registered the mod's loader, a retry cannot succeed — the coordinator and
     * McdpProvider reject a second registration — so the retry must report the first failure,
     * not an "already registered" that hides it. The lazy populator retries by design.
     */
    @Test
    void aFailureAfterTheLoaderIsRegisteredIsWhatARetryReports(@TempDir Path tmp) throws Exception {
        String modId = "retry_after_register_" + Long.toHexString(System.nanoTime());
        Path root = Files.createDirectories(tmp.resolve(modId));
        writeText(root, MANIFEST_PATH, "lang = \"java\"\n");
        writeText(root, "META-INF/mcdp-bridges.toml", "this is [[ not toml");
        JarContents contents = open(root);
        IModInfo info = modInfo(modId, "mcdepprovider", stub(IModFile.class, Map.of(
                "getFilePath", a -> root,
                "getContents", a -> contents)), null);

        RuntimeException first = assertThrows(RuntimeException.class, () -> call(McdpLanguageLoader.class,
                "ensureRegistered", registeredType(), new Class<?>[] {IModInfo.class}, info));
        assertNotNull(McdpProvider.loaderFor(modId),
                "premise: the body failed after registering the loader");
        RuntimeException retry = assertThrows(RuntimeException.class, () -> call(McdpLanguageLoader.class,
                "ensureRegistered", registeredType(), new Class<?>[] {IModInfo.class}, info));
        assertSame(first, retry.getCause(),
                "the retry must carry the first failure, got: " + retry);
    }
}

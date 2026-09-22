package de.lhns.mcdp.neoforge;

import de.lhns.mcdp.api.McdpProvider;
import de.lhns.mcdp.core.ModClassLoader;
import de.lhns.mcdp.deps.Manifest;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageLoader;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Type;

import java.lang.annotation.ElementType;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code McdpLanguageLoader} on the {@code IModFile.findResource} surface. Compiled twice: by
 * the 1.21.1 band against loader 4.0.42, and by the 1.20.6 band against loader 3.0.45 — both
 * bands' loaders are the same source now, and each run links against the SPI that band ships.
 * The FML-10 tree ({@code neoforge-1.21.11}) has its own suite, since it reads mod jars through
 * {@code JarContents} instead.
 *
 * <p>Every mod id is unique to its test: {@code McdpProvider} and the loader's registries are
 * process-global and not resettable from this package. Seams are reached through
 * {@link NeoForgeTestSupport#call} — see there for why.
 */
class McdpLanguageLoaderTest {

    private static final String MANIFEST_PATH = "META-INF/mcdepprovider.toml";
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    /**
     * FML matched the mod to our loader iff {@code IModInfo.getLoader().name()} is ours. On the
     * 1.20.6 band this used to walk {@code IModFile.getLoaders()}, compiled against standalone
     * {@code neoforgespi:8.0.4}; loader 3.0.45 has no such method, so on the SPI NeoForge 20.6
     * ships this threw {@code NoSuchMethodError} — inside the lazy populator, outside any try.
     */
    @Test
    void isMcdpModAsksTheLanguageLoaderFmlMatched(@TempDir Path tmp) {
        IModInfo ours = modInfo("spi_ours", "mcdepprovider", dirModFile(tmp), null);
        IModInfo theirs = modInfo("spi_theirs", "javafml", dirModFile(tmp), null);

        assertEquals(true, isMcdpMod(ours));
        assertEquals(false, isMcdpMod(theirs));
    }

    /**
     * McdpProvider re-runs its populator after a throw, but never after a normal return. With
     * no LoadingModList the populator used to return quietly, so every later miss — including
     * those after FML built the list — reported "no auto-bridge registered" and never populated.
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
     * One unusable manifest must cost only its own mod its promotion. The whole walk used to sit
     * under a {@code catch (Throwable)} that fell back to an empty selection, so a short SHA or a
     * versionless coordinate in any one mod silently switched promotion off for all of them.
     */
    @Test
    void promotionSelectionLeavesAnUnusableManifestOut(@TempDir Path tmp) throws Exception {
        IModInfo newer = mcdpMod(tmp, "promo_newer", manifest(
                "org.scala-lang:scala-library:2.13.14", SHA_A));
        IModInfo older = mcdpMod(tmp, "promo_older", manifest(
                "org.scala-lang:scala-library:2.13.10", SHA_B));
        IModInfo shortSha = mcdpMod(tmp, "promo_short_sha", manifest(
                "org.scala-lang:scala-library:2.13.15", "abc"));
        IModInfo noVersion = mcdpMod(tmp, "promo_no_version", manifest(
                "org.scala-lang:scala-library", SHA_B));
        IModInfo notOurs = modInfo("promo_javafml", "javafml", stub(IModFile.class, Map.of(
                "findResource", a -> {
                    throw new AssertionError("read the manifest of a mod mcdp does not load");
                })), null);

        Object selected = call(McdpLanguageLoader.class, "selectPromotions", Map.class,
                new Class<?>[] {List.class},
                List.of(shortSha, newer, noVersion, notOurs, older));

        assertEquals(Map.of("org.scala-lang:scala-library", new Manifest.Library(
                        "org.scala-lang:scala-library:2.13.14", "https://example.invalid/lib.jar",
                        SHA_A)),
                selected);
    }

    /**
     * The per-mod registration lock. The first caller parks inside its manifest read; the
     * second must then block on the lock rather than read the manifest itself. Against a
     * check-then-act without the lock the second caller reads the manifest too and never
     * blocks — no timing luck involved either way.
     */
    @Test
    void ensureRegisteredRunsOnceUnderContention(@TempDir Path tmp) throws Exception {
        String modId = "race_mod_" + Long.toHexString(System.nanoTime());
        Path root = Files.createDirectories(tmp.resolve(modId));
        writeText(root, MANIFEST_PATH, "lang = \"java\"\n");

        CountDownLatch inManifestRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger manifestReads = new AtomicInteger();
        IModFile file = stub(IModFile.class, Map.of(
                "getFilePath", a -> root,
                "findResource", a -> {
                    String name = String.join("/", (String[]) a[0]);
                    if (name.equals(MANIFEST_PATH) && manifestReads.incrementAndGet() == 1) {
                        inManifestRead.countDown();
                        awaitQuietly(release);
                    }
                    return root.resolve(name);
                }));
        IModInfo info = modInfo(modId, "mcdepprovider", file, null);

        AtomicReference<Object> first = new AtomicReference<>();
        AtomicReference<Object> second = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t1 = new Thread(() -> register(info, first, failure), "race-1");
        Thread t2 = new Thread(() -> register(info, second, failure), "race-2");

        t1.start();
        assertTrue(inManifestRead.await(30, TimeUnit.SECONDS), "first caller never read the manifest");
        t2.start();
        spinUntil(() -> t2.getState() == Thread.State.BLOCKED || !t2.isAlive());
        Thread.State secondWhileFirstHeld = t2.getState();
        release.countDown();
        t1.join(TimeUnit.SECONDS.toMillis(30));
        t2.join(TimeUnit.SECONDS.toMillis(30));

        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(Thread.State.BLOCKED, secondWhileFirstHeld,
                "the second caller must wait on the per-mod lock, not run the registration");
        assertEquals(1, manifestReads.get(), "manifest read more than once");
        assertSame(first.get(), second.get(), "both callers must get the same Registered");
        assertSame(read(first.get(), "loader", ModClassLoader.class), McdpProvider.loaderFor(modId),
                "McdpProvider must hold the loader the cache returned");
    }

    /**
     * {@code [[mixins]]} from FML's own view of neoforge.mods.toml, in the dev-run shape
     * ({@code findResource} answering a plain directory path). The only direct evidence this path
     * registers anything: neoforge-example's mixin marker would print without it, because
     * auto-bridges resolve through a separate registry.
     */
    @Test
    void mixinOwnersComeFromTheModsTomlMixinsBlock(@TempDir Path tmp) throws Exception {
        String modId = "mixin_owner_dir_" + Long.toHexString(System.nanoTime());
        writeText(tmp, "foo.mixins.json",
                "{\"package\":\"com.example.nfmixin\",\"mixins\":[\"FooMixin\"],"
                        + "\"client\":[\"BarClient\"]}");
        McdpProvider.registerMod(modId, emptyLoader(modId));

        IModInfo info = modInfo(modId, "mcdepprovider", dirModFile(tmp),
                mixinsBlock("foo.mixins.json", "absent.mixins.json"));

        assertEquals(List.of("com.example.nfmixin.FooMixin", "com.example.nfmixin.BarClient"),
                registerMixinOwners(info, modId));
    }

    /** The production shape: {@code findResource} answers ZIP-filesystem paths into the jar. */
    @Test
    void mixinOwnersResolveInsideAPackagedJar(@TempDir Path tmp) throws Exception {
        String modId = "mixin_owner_jar_" + Long.toHexString(System.nanoTime());
        Path jar = tmp.resolve("mod.jar");
        writeZip(jar, "nested/bar.mixins.json",
                "{\"package\":\"com.example.nfjar\",\"server\":[\"BazServer\"]}");
        McdpProvider.registerMod(modId, emptyLoader(modId));

        try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
            IModFile file = stub(IModFile.class, Map.of(
                    "getFilePath", a -> jar,
                    "findResource", a -> fs.getPath(String.join("/", (String[]) a[0]))));
            IModInfo info = modInfo(modId, "mcdepprovider", file,
                    mixinsBlock("nested/bar.mixins.json"));

            assertEquals(List.of("com.example.nfjar.BazServer"), registerMixinOwners(info, modId));
        }
    }

    /**
     * A jar may carry several {@code @Mod} classes. The entry class is the one whose value is
     * this mod's id — never simply the first {@code @Mod} in the scan.
     */
    @Test
    void entryClassIsTheModAnnotationCarryingThisModsId() {
        ModFileScanData scan = new ModFileScanData();
        scan.getAnnotations().add(modAnnotation(OtherModEntry.class, "entry_other_mod"));
        scan.getAnnotations().add(modAnnotation(ThisModEntry.class, "entry_this_mod"));

        assertSame(ThisModEntry.class, loadEntryClass(scan, "entry_this_mod"));
    }

    @Test
    void entryClassLookupFailsRatherThanTakingAnotherModsClass() {
        ModFileScanData scan = new ModFileScanData();
        scan.getAnnotations().add(modAnnotation(OtherModEntry.class, "entry_other_mod"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> loadEntryClass(scan, "entry_missing_mod"));
        assertTrue(ex.getMessage().contains("@Mod(\"entry_missing_mod\")"), ex.getMessage());
    }

    // --- fixtures ----------------------------------------------------------------------------

    public static final class ThisModEntry {}

    public static final class OtherModEntry {}

    // --- helpers -----------------------------------------------------------------------------

    private static boolean isMcdpMod(IModInfo info) {
        return (Boolean) call(McdpLanguageLoader.class, "isMcdpMod", boolean.class,
                new Class<?>[] {IModInfo.class}, info);
    }

    private static List<?> registerMixinOwners(IModInfo info, String modId) {
        return (List<?>) call(McdpLanguageLoader.class, "registerMixinOwnersForNeoForgeMod", List.class,
                new Class<?>[] {IModInfo.class, String.class}, info, modId);
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

    private static IModInfo mcdpMod(Path tmp, String modId, String manifestToml) throws Exception {
        Path root = Files.createDirectories(tmp.resolve(modId));
        writeText(root, MANIFEST_PATH, manifestToml);
        return modInfo(modId, "mcdepprovider", dirModFile(root), null);
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

    /** Dev-run shape: a directory root, and FML-style speculative paths for absent entries. */
    private static IModFile dirModFile(Path root) {
        return stub(IModFile.class, Map.of(
                "getFilePath", a -> root,
                "findResource", a -> root.resolve(String.join("/", (String[]) a[0]))));
    }

    private static IConfigurable mixinsBlock(String... configs) {
        List<IConfigurable> entries = new java.util.ArrayList<>();
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
                McdpLanguageLoaderTest.class.getClassLoader(), List.of());
    }

    private static void writeText(Path root, String name, String text) throws Exception {
        Path p = root.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    private static void writeZip(Path zip, String name, String text) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry(name));
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
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
        IModInfo info = modInfo(modId, "mcdepprovider", stub(IModFile.class, Map.of(
                "getFilePath", a -> root,
                "findResource", a -> root.resolve(String.join("/", (String[]) a[0])))), null);

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

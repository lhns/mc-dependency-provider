package de.lhns.mcdp.forge;

import de.lhns.mcdp.api.McdpProvider;
import de.lhns.mcdp.core.StdlibPromotion;
import de.lhns.mcdp.deps.Manifest;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.language.ModFileScanData;
import net.minecraftforge.forgespi.locating.IModFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Type;

import java.io.OutputStream;
import java.lang.annotation.ElementType;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static de.lhns.mcdp.forge.ForgeStubs.fileInfo;
import static de.lhns.mcdp.forge.ForgeStubs.langOnly;
import static de.lhns.mcdp.forge.ForgeStubs.library;
import static de.lhns.mcdp.forge.ForgeStubs.modFile;
import static de.lhns.mcdp.forge.ForgeStubs.modInDirectory;
import static de.lhns.mcdp.forge.ForgeStubs.modInfo;
import static de.lhns.mcdp.forge.ForgeStubs.sha;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link McdpLanguageProvider} outside FML, through the package-private seams it exposes.
 *
 * <p>Runs on all four Forge bands (forge-1.17/-1.19/-1.20 point their test source set here), so
 * it sticks to SPI surface that is identical on forgespi 4.0.x, 6.0.0 and 7.1.x and to Java 16.
 *
 * <p>Every test uses its own modId: {@link McdpProvider}'s registries and this class's
 * {@code REGISTERED} cache are process-global, and nothing resets them between tests.
 */
class McdpLanguageProviderTest {

    private static final String MOD_DESC = "Lnet/minecraftforge/fml/common/Mod;";

    // --- Item 4: one malformed manifest must not break promotion for everyone ----------------

    /**
     * The promotion selection is computed once for every mcdp mod and cached only on success,
     * so an exception escaping one mod's manifest read failed <em>every</em> mod's registration.
     * The old loop caught only {@link IllegalStateException}; each bad mod below throws
     * something else, and they come first so an escape aborts before the good mod is reached.
     */
    @Test
    void aMalformedManifestIsLeftOutOfPromotionInsteadOfFailingEveryone(@TempDir Path tmp)
            throws Exception {
        // ManifestIo casts each `libraries` entry to a table: a string array -> ClassCastException.
        IModInfo wrongType = modInDirectory("promo_cce", tmp.resolve("cce"),
                "lang = \"java\"\nlibraries = [\"oops\"]\n");
        // Manifest.Library rejects a SHA-256 that is not 64 characters -> IllegalArgumentException.
        IModInfo shortSha = modInDirectory("promo_short_sha", tmp.resolve("sha"),
                langOnly("java") + library("org.example:thing:1.0", "abc"));
        // Reads fine, but StdlibPromotion.stemOf cannot parse it -> IllegalArgumentException from
        // the shared selection pass, not from the read.
        IModInfo noVersion = modInDirectory("promo_no_version", tmp.resolve("nover"),
                langOnly("java") + library("org.example:thing", sha('b')));
        // No manifest at all -> IllegalStateException (the one the old loop did catch).
        IModInfo missing = modInDirectory("promo_missing", tmp.resolve("missing"), null);
        // The SPI itself failing.
        IModInfo brokenSpi = ForgeStubs.proxy(IModInfo.class, Map.of(
                "getModId", a -> "promo_broken_spi",
                "getOwningFile", a -> { throw new IllegalStateException("no owning file"); }));
        IModInfo good = modInDirectory("promo_good", tmp.resolve("good"),
                langOnly("scala") + library("org.scala-lang:scala-library:2.13.14", sha('a')));

        List<Manifest> manifests;
        try (ForgeStubs.LogCapture log = new ForgeStubs.LogCapture()) {
            manifests = McdpLanguageProvider.manifestsForPromotion(
                    List.of(wrongType, shortSha, noVersion, missing, brokenSpi, good));

            for (String bad : List.of("promo_cce", "promo_short_sha", "promo_no_version",
                    "promo_missing", "promo_broken_spi")) {
                assertTrue(log.anyWarningMentions(bad), "no warning naming " + bad);
            }
        }

        assertEquals(1, manifests.size(), "only the readable manifest may take part: " + manifests);
        assertEquals("scala", manifests.get(0).lang());
        // And what survives must be selectable: this is the call that used to throw for everyone.
        assertEquals(Set.of("org.scala-lang:scala-library"),
                StdlibPromotion.defaults().selectPromotions(manifests).keySet());
    }

    // --- Item 5 and the isMcdpMod contract ---------------------------------------------------

    @Test
    void isMcdpModWhenTheModFileDeclaresTheLanguage(@TempDir Path tmp) {
        IModFile file = modFile(tmp, name -> {
            throw new AssertionError("the language declaration alone must decide: " + name);
        });
        IModInfo info = modInfo("lang_decl_mod", fileInfo(file, List.of(
                new IModFileInfo.LanguageSpec("javafml", IModInfo.UNBOUNDED),
                new IModFileInfo.LanguageSpec(McdpLanguageProvider.LANGUAGE_ID, IModInfo.UNBOUNDED))));

        assertTrue(McdpLanguageProvider.isMcdpMod(info));
    }

    /** The fallback for a mod file whose language list is not populated. */
    @Test
    void isMcdpModWhenOnlyTheManifestIsPresent(@TempDir Path tmp) throws Exception {
        ForgeStubs.writeManifest(tmp, langOnly("java"));
        IModInfo info = modInfo("manifest_only_mod", fileInfo(modFile(tmp, tmp::resolve), List.of(
                new IModFileInfo.LanguageSpec("javafml", IModInfo.UNBOUNDED))));

        assertTrue(McdpLanguageProvider.isMcdpMod(info));
    }

    @Test
    void isNotMcdpModWithNeitherTheLanguageNorTheManifest(@TempDir Path tmp) {
        IModInfo info = modInfo("plain_forge_mod", fileInfo(modFile(tmp, tmp::resolve), List.of(
                new IModFileInfo.LanguageSpec("javafml", IModInfo.UNBOUNDED))));

        assertFalse(McdpLanguageProvider.isMcdpMod(info));
    }

    /**
     * Item 5. The answer stays {@code false} — the loader must not fail the boot over a mod it
     * cannot inspect — but an mcdp mod that lands here silently runs on another mod's promoted
     * stdlib, so it has to be said, naming the mod. The old code returned false without a word.
     */
    @Test
    void isMcdpModWarnsNamingTheModWhenItCannotTell() {
        IModFileInfo exploding = ForgeStubs.proxy(IModFileInfo.class, Map.of(
                "requiredLanguageLoaders", a -> { throw new IllegalStateException("spi boom"); }));
        IModInfo info = modInfo("uninspectable_mod", exploding);

        try (ForgeStubs.LogCapture log = new ForgeStubs.LogCapture()) {
            assertFalse(McdpLanguageProvider.isMcdpMod(info));
            assertTrue(log.anyWarningMentions("uninspectable_mod"),
                    "expected a WARNING naming the mod, got " + log.records.size() + " record(s)");
        }
    }

    // --- readManifest ------------------------------------------------------------------------

    /**
     * {@code findResource} hands back a speculative path even for resources no root holds, so
     * a packaged mod whose union view misses the manifest arrives with a path that does not
     * exist. readManifest then opens the mod jar itself.
     */
    @Test
    void readManifestFallsBackToTheModJarItself(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("mod.jar");
        writeJar(jar, McdpLanguageProvider.MANIFEST_PATH, langOnly("kotlin"));

        Manifest viaMissingPath = McdpLanguageProvider.readManifest(
                jar, tmp.resolve("not-there/mcdepprovider.toml"), "jar_fallback_mod");
        Manifest viaNull = McdpLanguageProvider.readManifest(jar, null, "jar_fallback_mod");

        assertEquals("kotlin", viaMissingPath.lang());
        assertEquals("kotlin", viaNull.lang());
    }

    @Test
    void readManifestFailsNamingTheModWhenThereIsNone(@TempDir Path tmp) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> McdpLanguageProvider.readManifest(tmp, tmp.resolve("absent.toml"), "no_manifest_mod"));
        assertTrue(e.getMessage().startsWith("mcdepprovider: no_manifest_mod "), e.getMessage());
        assertTrue(e.getMessage().contains(McdpLanguageProvider.MANIFEST_PATH), e.getMessage());
    }

    // --- The LoadingModList path -------------------------------------------------------------

    /**
     * On the test classpath fmlloader is present (fmlcore's runtime dependency) but nothing has
     * built the list, so {@code LoadingModList.get()} returns null — the same state the static
     * initializer sees in production, before FML populates it. That must mean "no mods yet",
     * never an exception: {@code ensurePromotionInitialized} and the lazy populator both iterate
     * the result.
     *
     * <p>The first half checks the premise, so this test cannot pass by exercising the
     * class-not-found branch instead.
     */
    @Test
    void anUnbuiltLoadingModListMeansNoMods() throws Exception {
        Class<?> lml = Class.forName("net.minecraftforge.fml.loading.LoadingModList");
        Method get = lml.getMethod("get");
        assertNull(get.invoke(null), "premise: nothing has built the LoadingModList");

        List<IModInfo> mods = McdpLanguageProvider.allMcdpMods();
        assertNotNull(mods);
        assertTrue(mods.isEmpty(), mods.toString());
    }

    // --- Item 8: several @Mod classes in one jar ---------------------------------------------

    /**
     * FML dispatches {@code loadMod} per mod ID through {@code getTargets()}; a mod ID with no
     * loader there never loads. The old visitor registered only the first {@code @Mod} of an
     * unordered set.
     */
    @Test
    void fileVisitorRegistersALoaderForEveryModInTheJar() {
        ModFileScanData scan = new ModFileScanData();
        scan.getAnnotations().add(modAnnotation("com.example.First", "multi_first"));
        scan.getAnnotations().add(otherAnnotation("com.example.First"));
        scan.getAnnotations().add(modAnnotation("com.example.Second", "multi_second"));

        new McdpLanguageProvider().getFileVisitor().accept(scan);

        assertEquals(Set.of("multi_first", "multi_second"), scan.getTargets().keySet());
    }

    /** Neither the scan order nor a non-{@code @Mod} annotation may decide the entry class. */
    @Test
    void entryClassIsTheModAnnotatedClassForThatModId() {
        ModFileScanData scan = new ModFileScanData();
        scan.getAnnotations().add(otherAnnotation("com.example.Helper"));
        scan.getAnnotations().add(modAnnotation("com.example.First", "entry_first"));
        scan.getAnnotations().add(modAnnotation("com.example.Second", "entry_second"));

        assertEquals("com.example.Second",
                McdpLanguageProvider.entryClassFor("entry_second", scan));
        assertEquals("com.example.First",
                McdpLanguageProvider.entryClassFor("entry_first", scan));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> McdpLanguageProvider.entryClassFor("entry_third", scan));
        assertTrue(e.getMessage().startsWith("mcdepprovider:"), e.getMessage());
    }

    // --- Item 10 -----------------------------------------------------------------------------

    @Test
    void progressSizesDoNotDependOnTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1.5 KB", McdpLanguageProvider.JulProgressListener.humanBytes(1536L));
            assertEquals("1.5 MB", McdpLanguageProvider.JulProgressListener.humanBytes(1536L * 1024L));
            assertEquals("512 B", McdpLanguageProvider.JulProgressListener.humanBytes(512L));
        } finally {
            Locale.setDefault(previous);
        }
    }

    // --- The ensureRegistered race -----------------------------------------------------------

    /**
     * ADR-0028's two entry points — FML's {@code loadMod} worker and the lazy populator — can
     * reach {@code ensureRegistered} for one mod at the same time. The body must run once:
     * it ends in a plain {@code McdpProvider.registerMod} put, so two runs leave McdpProvider
     * and the local cache holding different loaders.
     *
     * <p>Deterministic, no sleeps: thread A parks inside the first call of the body (the
     * manifest {@code findResource}) while thread B is started. We wait until B is either
     * {@code BLOCKED} on a monitor A owns (the per-mod lock doing its job) or has itself reached
     * {@code findResource} (a check-then-act letting it through), then release A. Only the
     * first manifest lookup parks, so under a check-then-act B runs the whole body unhindered.
     */
    @Test
    void concurrentEnsureRegisteredRunsTheBodyOnce(@TempDir Path tmp) throws Exception {
        String modId = "race_mod";
        Path root = Files.createDirectories(tmp.resolve("race"));
        ForgeStubs.writeManifest(root, langOnly("java"));

        AtomicInteger manifestLookups = new AtomicInteger();
        CountDownLatch aInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Function<String, Path> finder = name -> {
            if (name.equals(McdpLanguageProvider.MANIFEST_PATH)
                    && manifestLookups.incrementAndGet() == 1) {
                aInside.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return root.resolve(name);
        };
        IModInfo info = modInfo(modId, fileInfo(modFile(root, finder), List.of()));

        AtomicReference<McdpLanguageProvider.Registered> aResult = new AtomicReference<>();
        AtomicReference<McdpLanguageProvider.Registered> bResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread a = new Thread(() -> run(info, aResult, failure), "race-A");
        Thread b = new Thread(() -> run(info, bResult, failure), "race-B");

        a.start();
        assertTrue(aInside.await(30, TimeUnit.SECONDS), "thread A never reached findResource");
        b.start();

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            if (manifestLookups.get() >= 2) break;          // B got into the body: no lock
            if (b.getState() == Thread.State.TERMINATED) break;
            if (b.getState() == Thread.State.BLOCKED) {
                ThreadInfo ti = mx.getThreadInfo(b.getId());
                if (ti != null && ti.getLockOwnerId() == a.getId()) break;  // B waits on A's lock
            }
            if (System.nanoTime() > deadline) {
                release.countDown();
                fail("thread B neither blocked on A's lock nor entered the body; state "
                        + b.getState());
            }
            Thread.onSpinWait();
        }
        release.countDown();
        a.join(TimeUnit.SECONDS.toMillis(30));
        b.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(a.isAlive() || b.isAlive(), "a registering thread did not finish");
        if (failure.get() != null) throw new AssertionError("registration failed", failure.get());

        assertEquals(1, manifestLookups.get(), "the registration body ran more than once");
        assertNotNull(aResult.get());
        assertSame(aResult.get(), bResult.get(), "both callers must get the one Registered");
        assertSame(aResult.get().loader(), McdpProvider.loaderFor(modId),
                "McdpProvider must hold the loader the cache hands out");
    }

    private static void run(IModInfo info,
                            AtomicReference<McdpLanguageProvider.Registered> out,
                            AtomicReference<Throwable> failure) {
        try {
            out.set(McdpLanguageProvider.ensureRegistered(info));
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    static ModFileScanData.AnnotationData modAnnotation(String entryFqn, String modId) {
        Map<String, Object> values = new HashMap<>();
        values.put("value", modId);
        return new ModFileScanData.AnnotationData(Type.getType(MOD_DESC), ElementType.TYPE,
                Type.getObjectType(entryFqn.replace('.', '/')), null, values);
    }

    private static ModFileScanData.AnnotationData otherAnnotation(String onClass) {
        Map<String, Object> values = new HashMap<>();
        values.put("value", "not-a-mod-id");
        return new ModFileScanData.AnnotationData(
                Type.getType("Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;"),
                ElementType.TYPE, Type.getObjectType(onClass.replace('.', '/')), null, values);
    }

    private static void writeJar(Path jar, String entry, String content) throws Exception {
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(out, manifest)) {
            jos.putNextEntry(new JarEntry(entry));
            jos.write(content.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }

    /**
     * Once the body has registered the mod's loader, a retry cannot succeed — the coordinator and
     * McdpProvider reject a second registration — so the retry must report the first failure,
     * not an "already registered" that hides it. The lazy populator retries by design.
     */
    @Test
    void aFailureAfterTheLoaderIsRegisteredIsWhatARetryReports(@TempDir Path tmp) throws Exception {
        String modId = "retry_after_register_mod";
        Path root = Files.createDirectories(tmp.resolve("retry"));
        ForgeStubs.writeManifest(root, langOnly("java"));
        Files.writeString(root.resolve("META-INF/mcdp-bridges.toml"), "this is [[ not toml");
        IModInfo info = modInfo(modId, fileInfo(modFile(root, root::resolve), List.of()));

        IllegalStateException first = assertThrows(IllegalStateException.class,
                () -> McdpLanguageProvider.ensureRegistered(info));
        assertNotNull(McdpProvider.loaderFor(modId),
                "premise: the body failed after registering the loader");
        IllegalStateException retry = assertThrows(IllegalStateException.class,
                () -> McdpLanguageProvider.ensureRegistered(info));
        assertSame(first, retry.getCause(),
                "the retry must carry the first failure, got: " + retry);
    }
}

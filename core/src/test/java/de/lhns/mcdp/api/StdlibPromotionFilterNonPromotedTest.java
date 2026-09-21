package de.lhns.mcdp.api;

import de.lhns.mcdp.core.StdlibPromotion;
import de.lhns.mcdp.deps.Manifest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for {@link StdlibPromotion#filterNonPromoted}, previously copy-pasted as a private
 * method into all five platform-adapter source files (Fabric's {@code McdpPreLaunch}, the three
 * NeoForge {@code McdpLanguageLoader}s, Forge's {@code McdpLanguageProvider}) and tested by none
 * of them.
 *
 * <p>Filed under {@code de.lhns.mcdp.api} alongside the other adapter-contract tests; the method
 * is public API of {@code core} so the package split costs nothing.
 */
public class StdlibPromotionFilterNonPromotedTest {

    private static final String SCALA3 = "org.scala-lang:scala3-library_3";
    private static final String KOTLIN = "org.jetbrains.kotlin:kotlin-stdlib";

    private static Manifest.Library lib(String coords) {
        return new Manifest.Library(coords, "https://example/x.jar", "0".repeat(64));
    }

    private static Manifest manifest(String... coords) {
        List<Manifest.Library> libs = java.util.Arrays.stream(coords).map(
                StdlibPromotionFilterNonPromotedTest::lib).toList();
        return new Manifest("scala", List.of(), libs);
    }

    /** One jar path per declared coordinate, named after it — so a mispaired index is visible. */
    private static List<Path> jarsFor(Manifest m) {
        return m.libraries().stream()
                .map(l -> Path.of("/cache", l.coords().replace(':', '_') + ".jar"))
                .toList();
    }

    private static Path jar(String coords) {
        return Path.of("/cache", coords.replace(':', '_') + ".jar");
    }

    /**
     * The split itself, with the promoted entry in the middle so that pairing by index and
     * pairing by output position disagree: a body that read {@code resolvedLibs.get(out.size())}
     * would return cats + scala3 + circe instead of cats + circe + guava.
     */
    @Test
    void keepsNonPromotedJarsAndDropsPromotedOnes() {
        Manifest m = manifest(
                "org.typelevel:cats-core_3:2.13.0",
                SCALA3 + ":3.5.2",
                "io.circe:circe-core_3:0.14.10",
                "com.google.guava:guava:33.0.0-jre");
        Map<String, Manifest.Library> selected = Map.of(SCALA3, lib(SCALA3 + ":3.5.2"));

        assertEquals(
                List.of(jar("org.typelevel:cats-core_3:2.13.0"),
                        jar("io.circe:circe-core_3:0.14.10"),
                        jar("com.google.guava:guava:33.0.0-jre")),
                StdlibPromotion.filterNonPromoted(m, jarsFor(m), selected));
    }

    /**
     * Every occurrence of a promoted stem goes, not just the first — a mod can declare two
     * coordinates under one stem (classifier variants, a transitive pin plus a direct one).
     */
    @Test
    void dropsEveryJarSharingAPromotedStem() {
        Manifest m = manifest(SCALA3 + ":3.5.2", "org.typelevel:cats-core_3:2.13.0", SCALA3 + ":3.5.3");
        Map<String, Manifest.Library> selected = Map.of(SCALA3, lib(SCALA3 + ":3.5.3"));

        assertEquals(List.of(jar("org.typelevel:cats-core_3:2.13.0")),
                StdlibPromotion.filterNonPromoted(m, jarsFor(m), selected));
    }

    /** Nothing promoted (the default, non-opt-in adapter path) → the list passes through whole. */
    @Test
    void emptyPromotionSetKeepsEveryJar() {
        Manifest m = manifest(SCALA3 + ":3.5.2", "org.typelevel:cats-core_3:2.13.0");
        List<Path> jars = jarsFor(m);

        assertEquals(jars, StdlibPromotion.filterNonPromoted(m, jars, Map.of()));
    }

    /** Promotion set covering every declared lib → empty list; the shared loader carries all of it. */
    @Test
    void promotionSetCoveringEverythingYieldsEmptyList() {
        Manifest m = manifest(SCALA3 + ":3.5.2", KOTLIN + ":2.0.0");
        Map<String, Manifest.Library> selected =
                Map.of(SCALA3, lib(SCALA3 + ":3.5.2"), KOTLIN, lib(KOTLIN + ":2.0.0"));

        assertEquals(List.of(), StdlibPromotion.filterNonPromoted(m, jarsFor(m), selected));
    }

    /**
     * Result must agree entry-for-entry with {@link StdlibPromotion#stripPromoted}: the adapters
     * hand the two outputs to {@code LoaderCoordinator.register} as a manifest/URL pair, so a
     * length or ordering disagreement is a mod booting against the wrong jars.
     */
    @Test
    void agreesWithStripPromotedEntryForEntry() {
        StdlibPromotion policy = new StdlibPromotion(Set.of(SCALA3, KOTLIN));
        Manifest m = manifest(
                "org.typelevel:cats-core_3:2.13.0",
                SCALA3 + ":3.5.2",
                KOTLIN + ":2.0.0",
                "io.circe:circe-core_3:0.14.10");
        Map<String, Manifest.Library> selected = policy.selectPromotions(List.of(m));

        List<Manifest.Library> keptLibs = policy.stripPromoted(m, selected);
        List<Path> keptJars = StdlibPromotion.filterNonPromoted(m, jarsFor(m), selected);

        assertEquals(keptLibs.size(), keptJars.size());
        for (int i = 0; i < keptLibs.size(); i++) {
            assertEquals(jar(keptLibs.get(i).coords()), keptJars.get(i),
                    "entry " + i + " of the reduced manifest and the reduced jar list disagree");
        }
    }

    /**
     * Fewer resolved jars than declared libraries: the invariant must be reported as such. The
     * copy-pasted originals let {@code resolvedLibs.get(i)} fault with a bare
     * {@link IndexOutOfBoundsException} naming only an index.
     */
    @Test
    void throwsWhenFewerJarsThanDeclaredLibraries() {
        Manifest m = manifest("org.typelevel:cats-core_3:2.13.0", SCALA3 + ":3.5.2");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> StdlibPromotion.filterNonPromoted(m, List.of(jar("a:b:1")), Map.of()));
        assertTrue(ex.getMessage().contains("index-parallel"), ex.getMessage());
        assertTrue(ex.getMessage().contains("2 declared vs 1 resolved"), ex.getMessage());
    }

    /**
     * More resolved jars than declared libraries. This is the branch that mattered: the
     * copy-pasted originals iterated the manifest and returned successfully, silently leaving
     * the surplus jars off the mod's classpath — a green boot and a {@code NoClassDefFoundError}
     * much later.
     */
    @Test
    void throwsWhenMoreJarsThanDeclaredLibraries() {
        Manifest m = manifest("org.typelevel:cats-core_3:2.13.0");
        List<Path> jars = List.of(jar("org.typelevel:cats-core_3:2.13.0"), jar("io.circe:circe-core_3:0.14.10"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> StdlibPromotion.filterNonPromoted(m, jars, Map.of()));
        assertTrue(ex.getMessage().contains("1 declared vs 2 resolved"), ex.getMessage());
    }

    /** The length check runs before any coordinate parsing, so it wins over a malformed coord. */
    @Test
    void lengthCheckPrecedesCoordinateParsing() {
        Manifest m = manifest("not-a-maven-coordinate");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> StdlibPromotion.filterNonPromoted(m, List.of(), Map.of()));
        assertTrue(ex.getMessage().contains("index-parallel"), ex.getMessage());
    }
}

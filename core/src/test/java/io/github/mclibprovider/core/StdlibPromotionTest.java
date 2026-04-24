package io.github.mclibprovider.core;

import io.github.mclibprovider.deps.Manifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StdlibPromotionTest {

    private static Manifest modWithLibs(Manifest.Library... libs) {
        return new Manifest("scala", List.of(), List.of(libs));
    }

    private static Manifest.Library lib(String coords) {
        return new Manifest.Library(coords, "https://example/x.jar", "0".repeat(64));
    }

    @Test
    void picksHighestVersionAcrossMods() {
        StdlibPromotion p = new StdlibPromotion(Set.of("org.scala-lang:scala3-library_3"));
        Manifest a = modWithLibs(lib("org.scala-lang:scala3-library_3:3.5.2"));
        Manifest b = modWithLibs(lib("org.scala-lang:scala3-library_3:3.5.3"));
        Manifest c = modWithLibs(lib("org.scala-lang:scala3-library_3:3.4.9"));

        Map<String, Manifest.Library> selected = p.selectPromotions(List.of(a, b, c));
        assertEquals(1, selected.size());
        assertEquals("org.scala-lang:scala3-library_3:3.5.3",
                selected.get("org.scala-lang:scala3-library_3").coords());
    }

    @Test
    void nonPromotedLibsIgnored() {
        StdlibPromotion p = new StdlibPromotion(Set.of("org.scala-lang:scala3-library_3"));
        Manifest m = modWithLibs(
                lib("org.typelevel:cats-core_3:2.13.0"),
                lib("org.scala-lang:scala3-library_3:3.5.2"));
        Map<String, Manifest.Library> selected = p.selectPromotions(List.of(m));
        assertEquals(Set.of("org.scala-lang:scala3-library_3"), selected.keySet());
    }

    @Test
    void stripPromotedRemovesExactly() {
        StdlibPromotion p = new StdlibPromotion(Set.of("org.scala-lang:scala3-library_3"));
        Manifest m = modWithLibs(
                lib("org.typelevel:cats-core_3:2.13.0"),
                lib("org.scala-lang:scala3-library_3:3.5.2"),
                lib("io.circe:circe-core_3:0.14.10"));
        Map<String, Manifest.Library> selected = p.selectPromotions(List.of(m));

        List<Manifest.Library> kept = p.stripPromoted(m, selected);
        assertEquals(2, kept.size());
        assertTrue(kept.stream().noneMatch(l -> l.coords().startsWith("org.scala-lang:scala3-library_3")));
        // Order preserved.
        assertEquals("org.typelevel:cats-core_3:2.13.0", kept.get(0).coords());
        assertEquals("io.circe:circe-core_3:0.14.10", kept.get(1).coords());
    }

    @Test
    void multiplePromotedStemsHandledIndependently() {
        StdlibPromotion p = new StdlibPromotion(Set.of(
                "org.scala-lang:scala3-library_3",
                "org.jetbrains.kotlin:kotlin-stdlib"));
        Manifest a = modWithLibs(
                lib("org.scala-lang:scala3-library_3:3.5.2"),
                lib("org.jetbrains.kotlin:kotlin-stdlib:2.1.0"));
        Manifest b = modWithLibs(
                lib("org.scala-lang:scala3-library_3:3.5.3"),
                lib("org.jetbrains.kotlin:kotlin-stdlib:2.0.0"));
        Map<String, Manifest.Library> selected = p.selectPromotions(List.of(a, b));
        assertEquals(2, selected.size());
        assertEquals("3.5.3", StdlibPromotion.versionOf(selected.get("org.scala-lang:scala3-library_3").coords()));
        assertEquals("2.1.0", StdlibPromotion.versionOf(selected.get("org.jetbrains.kotlin:kotlin-stdlib").coords()));
    }

    @Test
    void versionCompareNumericsAndQualifiers() {
        assertTrue(StdlibPromotion.compareVersions("3.5.3", "3.5.2") > 0);
        assertTrue(StdlibPromotion.compareVersions("3.10.0", "3.9.9") > 0);
        assertTrue(StdlibPromotion.compareVersions("3.5.2", "3.5.2") == 0);
        // Release > RC > beta > alpha > snapshot.
        assertTrue(StdlibPromotion.compareVersions("3.5.2", "3.5.2-RC1") > 0);
        assertTrue(StdlibPromotion.compareVersions("3.5.2-RC2", "3.5.2-beta1") > 0);
        assertTrue(StdlibPromotion.compareVersions("3.5.2-beta1", "3.5.2-alpha2") > 0);
        assertTrue(StdlibPromotion.compareVersions("3.5.2-alpha2", "3.5.2-SNAPSHOT") > 0);
    }

    @Test
    void defaultsCoverScalaAndKotlin() {
        StdlibPromotion p = StdlibPromotion.defaults();
        assertTrue(p.promotedStems().contains("org.scala-lang:scala3-library_3"));
        assertTrue(p.promotedStems().contains("org.jetbrains.kotlin:kotlin-stdlib"));
    }

    @Test
    void coordsWithoutVersionRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StdlibPromotion.stemOf("com.example:lib"));
    }
}

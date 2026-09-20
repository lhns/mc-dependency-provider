plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.neoforged.net/releases")
    // Mojang's repo — fancymodloader:loader pulls transitive com.mojang:logging from here.
    maven("https://libraries.minecraft.net/")
}

// NeoForge for MC 1.21.11 (NeoForge 21.11.x, fancymodloader:loader 10.0.x).
//
// This band keeps its OWN source tree rather than pointing srcDirs at neoforge/ (the 1.21.1
// band, FML 4.0.x). The core SPI types are in fact unchanged — IModLanguageLoader, IModInfo,
// ModFileScanData (including getAnnotatedBy) and ModContainer have identical javap output on
// loader 4.0.42 and 10.0.36 — but two surfaces the adapter depends on broke at FML 10.0
// (i.e. at MC 1.21.10; 9.0.18, which pairs with 1.21.8, still has both):
//
//   1. `IModFile.findResource(String...)` removed. The cpw.mods.jarhandling.SecureJar view was
//      replaced by net.neoforged.fml.jarcontents.JarContents, which is stream-addressed and
//      offers no java.nio Path for a jar entry.
//   2. `FMLEnvironment.dist` (public static field) became `FMLEnvironment.getDist()`.
//
// Both are link-time failures, not soft ones, so one shared tree cannot serve both bands.
// See ADR-0030 for the full evidence.
mcdpBand {
    javaRelease.set(21)
    fmlModType.set("LIBRARY")
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // Same reasoning as the 1.21.1 band: pin the fancymodloader loader artifact rather than
    // standalone neoforgespi, because the loader jar is what re-ships the modernized top-level
    // net.neoforged.neoforgespi.language.IModLanguageLoader. 10.0.36 is what
    // net.neoforged:neoforge:21.11.45 (the newest 21.11 release) declares in its POM.
    compileOnly(libs.neoforge.fml.loader.mc12111)
    compileOnly(libs.neoforge.bus)
}

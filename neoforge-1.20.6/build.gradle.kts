plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.neoforged.net/releases")
    maven("https://libraries.minecraft.net/")
}

// NeoForge for MC 1.20.6 — first JPMS-era band, NeoForge SPI 8.0.x. Adapter ported from
// neoforge/ (21.x) with two 8.0.x-specific tweaks documented inline in McdpLanguageLoader.java:
//   1. info.getLoader().name() (9.0.x-only) → walk getOwningFile().getFile().getLoaders()
//   2. ModFileScanData.getAnnotatedBy() (9.0.x-only) → filter getAnnotations() by descriptor
// so this band keeps its own source tree rather than sharing neoforge/'s.
mcdpBand {
    javaRelease.set(21)
    fmlModType.set("LIBRARY")
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // NeoForge 20.6.x SPI line + matching FML loader (provides ModContainer, LogUtils,
    // LoadingModList, etc.). Pin to a 20.6.x-compatible fancymodloader version when 20.6.x
    // adapter implementation actually iterates against runServer.
    compileOnly(libs.neoforge.spi.mc1206)
    compileOnly(libs.neoforge.fml.loader.mc1206)
    compileOnly(libs.neoforge.bus)
}

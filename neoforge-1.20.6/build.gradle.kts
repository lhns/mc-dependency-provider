plugins {
    id("mcdp.neoforge-band")
}

// NeoForge for MC 1.20.6 (NeoForge 20.6.x, fancymodloader loader 3.0.x).
//
// Compiles against the loader artifact alone, like the 1.21.1 band. Loader 3.0.45 bundles its
// own `neoforgespi`, and it already has the 1.21 shape: `IModInfo.getLoader()`,
// `ModFileScanData.getAnnotatedBy()`, the top-level `IModLanguageLoader`, and no
// `IModFile.getLoaders()`. This band used to put the standalone `neoforgespi:8.0.4` ahead of
// it on the compile classpath, so the adapter compiled a `getLoaders()` call that NeoForge 20.6
// cannot link — a NoSuchMethodError the moment a mod's bootstrap-time mixin fired the lazy
// populator. McdpLanguageLoader.java is now the same as neoforge/'s; the one real 1.20.6 delta
// is McdpModContainer's `contextExtension`, which is why this band keeps its own tree.
mcdpBand {
    javaRelease.set(21)
    fmlModType.set("LIBRARY")
}

// The container suite is shared by all three NeoForge trees; the loader suite with the 1.21.1
// band, whose McdpLanguageLoader this one now matches. `srcDir`, not `setSrcDirs`, so this
// band's own src/test/java stays in.
sourceSets {
    test {
        java.srcDir(rootProject.file("neoforge-shared/src/test/java"))
        java.srcDir(rootProject.file("neoforge/src/test/java"))
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.neoforge.fml.loader.mc1206)
    compileOnly(libs.neoforge.bus)

    // Tests run on the SPI NeoForge 20.6 actually runs on: loader 3.0.45 and nothing else.
    // A standalone neoforgespi here would let a test pass against an SPI that never ships.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":core"))
    testImplementation(project(":deps-lib"))
    testImplementation(libs.neoforge.fml.loader.mc1206)
    testImplementation(libs.neoforge.bus)
}

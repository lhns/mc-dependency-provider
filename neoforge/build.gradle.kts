plugins {
    id("mcdp.neoforge-band")
}

// NeoForge for MC 1.21.x (FML 4.0.x). This tree is this band's only: the FML-10 removals mean
// neoforge-1.21.11/ carries its own port, and neoforge-26/ shares *that* one (ADR-0030, ADR-0032).
// Pointing a 26.x band here is exactly the mistake ADR-0032 was written to correct.
mcdpBand {
    javaRelease.set(21)
    fmlModType.set("LIBRARY")
}

// Tests: this band's own src/test/java (the McdpLanguageLoader suite, which neoforge-1.20.6
// also compiles against its identical copy) plus the suite all three NeoForge trees share.
// `srcDir`, not `setSrcDirs`.
sourceSets {
    test {
        java.srcDir(rootProject.file("neoforge-shared/src/test/java"))
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // Standalone neoforgespi:9.0.2 ships an outdated `IModLanguageProvider$IModLanguageLoader`
    // inner class; fancymodloader:loader:4.0.31+ re-ships a modernized top-level
    // `net.neoforged.neoforgespi.language.IModLanguageLoader`. Pin only the loader artifact
    // on compile so we bind against the FML 4.0.x shape that matches NeoForge 21.1.x runtime.
    compileOnly(libs.neoforge.fml.loader)
    compileOnly(libs.neoforge.bus)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":core"))
    testImplementation(project(":deps-lib"))
    testImplementation(libs.neoforge.fml.loader)
    testImplementation(libs.neoforge.bus)
}

// This subproject (`:neoforge-1.21`) is not published on its own. The band
// aggregator `:mcdp-1.21` (dir `multi/`) publishes the jar containing both the
// fabric and neoforge adapters. The shadowJar here remains produced for
// inspection/debugging and is consumed via `:mcdp-1.21`'s `bundle` configuration.

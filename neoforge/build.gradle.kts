plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.neoforged.net/releases")
    // Mojang's repo — fancymodloader:loader pulls transitive com.mojang:logging from here.
    maven("https://libraries.minecraft.net/")
}

// NeoForge for MC 1.21.x. This band owns the shared NeoForge adapter source; neoforge-26.1
// points its srcDirs here.
mcdpBand {
    javaRelease.set(21)
    fmlModType.set("LIBRARY")
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

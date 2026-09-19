plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.neoforged.net/releases")
    maven("https://libraries.minecraft.net/")
}

// NeoForge for MC 26.1.x — Mojang's calendar versioning era. The NeoForge SPI has not in
// fact diverged between 21.x and 26.1, so the adapter source is shared with neoforge/ and
// only this band's resources (the META-INF service file) live here. When the SPI does
// diverge, split the source tree back out into neoforge-26.1/src/main/java/.
mcdpBand {
    javaRelease.set(21)
    fmlModType.set("LIBRARY")
}

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("neoforge/src/main/java")))
        resources.setSrcDirs(listOf("src/main/resources"))
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // fancymodloader:loader 4.0.42 ships the modernized IModLanguageLoader; standalone
    // neoforgespi:9.0.x has an outdated inner-class form. Mirror neoforge/'s pin (1.21 band)
    // until NeoForge 26.x publishes a non-beta SPI line that warrants per-band divergence.
    compileOnly(libs.neoforge.fml.loader)
    compileOnly(libs.neoforge.bus)
}

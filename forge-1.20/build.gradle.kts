plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.minecraftforge.net/")
    // Test runtime only: fmlcore's runtime dependency fmlloader (1.19+) depends on Mojang's
    // com.mojang:logging, which LoadingModList's static initializer uses. Scoped to that group.
    maven("https://libraries.minecraft.net/") {
        content { includeGroup("com.mojang") }
    }
}

// Forge for MC 1.20.x (1.20.1). Adapter source is shared with the 1.18 band (identical
// forgespi usage); this file only pins the 7.x forgespi coordinates.
mcdpBand {
    javaRelease.set(17)
}

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("forge-1.18/src/main/java")))
        resources.setSrcDirs(listOf("src/main/resources"))
    }
    test {
        java.setSrcDirs(listOf(rootProject.file("forge-1.18/src/test/java")))
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // Forge SPI 7.x — Forge 1.20.x.
    compileOnly(libs.forge.spi.mc120)
    // fmlcore for Forge 1.20.1 — supplies net.minecraftforge.fml.ModContainer + lifecycle.
    compileOnly(libs.forge.fmlcore.mc120)
    compileOnly(libs.forge.eventbus.mc120)
    compileOnly(libs.asm)
    compileOnly(libs.maven.artifact)

    // The shared unit tests (forge-1.18/src/test/java) run on this band's own coordinates; see
    // forge-1.18/build.gradle.kts for why each of these is needed at test runtime.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":core"))
    testImplementation(project(":deps-lib"))
    testImplementation(libs.forge.spi.mc120)
    testImplementation(libs.forge.fmlcore.mc120)
    testImplementation(libs.forge.eventbus.mc120)
    testImplementation(libs.asm)
    testImplementation(libs.maven.artifact)
}

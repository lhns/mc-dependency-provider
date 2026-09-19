plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.minecraftforge.net/")
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
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // Forge SPI 7.x — Forge 1.20.x.
    compileOnly(libs.forge.spi.mc120)
    // fmlcore for Forge 1.20.1 — supplies net.minecraftforge.fml.ModContainer + lifecycle.
    compileOnly(libs.forge.fmlcore.mc120)
    compileOnly("net.minecraftforge:eventbus:6.0.5")
    compileOnly(libs.asm)
    compileOnly("org.apache.maven:maven-artifact:3.8.5")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testCompileOnly(libs.forge.spi.mc120)
    testCompileOnly(libs.forge.fmlcore.mc120)
}

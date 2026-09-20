plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.minecraftforge.net/")
}

// Forge for MC 1.17.x (1.17.1-37.1.2). Adapter source is shared with the 1.18 band, as the
// 1.20 band already does: Forge 1.17.1 and 1.18.2 both run forgespi 4.0.x and an identical
// `net.minecraftforge.fml.ModContainer` shape, so the source is applicable verbatim.
// This file only pins the 1.17 coordinates and the Java 16 target (ADR-0029).
mcdpBand {
    javaRelease.set(16)
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
    // Forge SPI 4.0.x — Forge 1.17.1 too, not 3.2.x: `fmlloader-1.17.1-37.1.2` depends on
    // `forgespi 4.0.+` and its `ModLoader` calls the 4.0 `loadMod(IModInfo, ModFileScanData,
    // ModuleLayer)` overload. See ADR-0029 and the catalog comment.
    compileOnly(libs.forge.spi.mc117)
    // fmlcore for Forge 1.17.1 — supplies net.minecraftforge.fml.ModContainer base class
    // and lifecycle types (ModLoadingStage, IExtensionPoint, IModBusEvent).
    compileOnly(libs.forge.fmlcore.mc117)
    // EventBus + ASM (TypeRef in ModFileScanData) — transitive of fmlcore at runtime, needed
    // explicitly at compile time. fmlcore 1.17.1-37.1.2 declares `eventbus 5.0.+`, the same
    // line the 1.18 band pins.
    compileOnly("net.minecraftforge:eventbus:5.0.7")
    compileOnly(libs.asm)
    compileOnly("org.apache.maven:maven-artifact:3.8.5")  // ArtifactVersion in IModInfo

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testCompileOnly(libs.forge.spi.mc117)
    testCompileOnly(libs.forge.fmlcore.mc117)
}

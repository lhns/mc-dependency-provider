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
    test {
        java.setSrcDirs(listOf(rootProject.file("forge-1.18/src/test/java")))
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
    // EventBus + ASM (TypeRef in ModFileScanData) — transitives of fmlcore at runtime, needed
    // explicitly at compile time; the catalog documents which eventbus line each band takes.
    compileOnly(libs.forge.eventbus.mc117)
    compileOnly(libs.asm)
    compileOnly(libs.maven.artifact)

    // The shared unit tests (forge-1.18/src/test/java) run on this band's own coordinates; see
    // forge-1.18/build.gradle.kts for why each of these is needed at test runtime.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":core"))
    testImplementation(project(":deps-lib"))
    testImplementation(libs.forge.spi.mc117)
    testImplementation(libs.forge.fmlcore.mc117)
    testImplementation(libs.forge.eventbus.mc117)
    testImplementation(libs.asm)
    testImplementation(libs.maven.artifact)
}

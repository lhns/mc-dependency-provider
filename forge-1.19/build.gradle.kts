plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.minecraftforge.net/")
}

// Forge for MC 1.19.x (1.19.2-43.5.2). Adapter source is shared with the 1.18 band, exactly as
// forge-1.17 and forge-1.20 already do: forgespi 6.0.x sits between the 4.0.x (1.17/1.18) and
// 7.x (1.20) surfaces the shared source already compiles against, and every type it touches is
// identical on all three. This file only pins the 1.19 coordinates (ADR-0031).
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
    // Forge SPI 6.0.x — the whole 1.19 line; the catalog explains why the pin is exact.
    compileOnly(libs.forge.spi.mc119)
    // fmlcore for Forge 1.19.2 — supplies net.minecraftforge.fml.ModContainer base class
    // and lifecycle types (ModLoadingStage, IExtensionPoint, IModBusEvent).
    compileOnly(libs.forge.fmlcore.mc119)
    // EventBus + ASM (TypeRef in ModFileScanData) — transitive of fmlcore at runtime, needed
    // explicitly at compile time. `fmlcore-1.19.2-43.5.2.pom` declares eventbus 6.0.3.
    compileOnly("net.minecraftforge:eventbus:6.0.3")
    compileOnly(libs.asm)
    compileOnly("org.apache.maven:maven-artifact:3.8.5")  // ArtifactVersion in IModInfo

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testCompileOnly(libs.forge.spi.mc119)
    testCompileOnly(libs.forge.fmlcore.mc119)
}

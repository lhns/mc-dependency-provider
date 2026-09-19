plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.minecraftforge.net/")
}

// Forge for MC 1.18.x. This band owns the real Forge adapter source (McdpLanguageProvider +
// McdpModContainer); forge-1.20 shares it via srcDirs.
mcdpBand {
    javaRelease.set(17)
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // Forge SPI 4.0.x — Forge 1.18.x. IModLanguageProvider, ModFileScanData, IModInfo.
    compileOnly(libs.forge.spi.mc118)
    // fmlcore for Forge 1.18.2 — supplies net.minecraftforge.fml.ModContainer base class
    // and lifecycle types (ModLoadingStage, IExtensionPoint, IModBusEvent).
    compileOnly(libs.forge.fmlcore.mc118)
    // EventBus + ASM (TypeRef in ModFileScanData) — transitive of fmlcore at runtime, needed
    // explicitly at compile time.
    compileOnly("net.minecraftforge:eventbus:5.0.7")
    compileOnly(libs.asm)
    compileOnly("org.apache.maven:maven-artifact:3.8.5")  // ArtifactVersion in IModInfo

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testCompileOnly(libs.forge.spi.mc118)
    testCompileOnly(libs.forge.fmlcore.mc118)
}

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
    // EventBus + ASM (TypeRef in ModFileScanData) — transitives of fmlcore at runtime, needed
    // explicitly at compile time; the catalog documents which eventbus line each band takes.
    compileOnly(libs.forge.eventbus.mc118)
    compileOnly(libs.asm)
    compileOnly(libs.maven.artifact)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Not testCompileOnly: McdpLanguageProvider implements IModLanguageProvider and its static
    // initializer reaches core/deps-lib, so loading the class under test needs all four at test
    // runtime, not just on the compile classpath.
    testImplementation(project(":core"))
    testImplementation(project(":deps-lib"))
    testImplementation(libs.forge.spi.mc118)
    testImplementation(libs.forge.fmlcore.mc118)
}

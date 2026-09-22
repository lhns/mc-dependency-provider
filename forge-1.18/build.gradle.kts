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
    // initializer reaches core/deps-lib, so loading the class under test needs all of these at
    // test runtime, not just on the compile classpath. eventbus: McdpModContainer builds a real
    // mod bus. asm: ModFileScanData.AnnotationData is built from asm Types. maven-artifact:
    // IModFileInfo.LanguageSpec takes a VersionRange. fmlcore also brings fmlloader at runtime
    // (its POM scope), which is what puts LoadingModList on the test classpath.
    //
    // forge-1.17, -1.19 and -1.20 compile and run this same test source set against their own
    // coordinates: the container's bytecode differs per band (BusBuilder is a class on eventbus
    // 5.0.7, an interface on 6.x), so a test here proves nothing about the others.
    // Shared test sources must therefore stay Java 16 (forge-1.17's --release).
    testImplementation(project(":core"))
    testImplementation(project(":deps-lib"))
    testImplementation(libs.forge.spi.mc118)
    testImplementation(libs.forge.fmlcore.mc118)
    testImplementation(libs.forge.eventbus.mc118)
    testImplementation(libs.asm)
    testImplementation(libs.maven.artifact)
}

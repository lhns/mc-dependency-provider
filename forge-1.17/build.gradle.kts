plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.minecraftforge.net/")
}

// Forge for MC 1.17.x. Still a stub adapter — getFileVisitor() throws.
mcdpBand {
    javaRelease.set(16)
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // 1.17 SPI surface only — fmlcore not pulled until per-band adapter implementation lands.
    compileOnly(libs.forge.spi.mc117)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testCompileOnly(libs.forge.spi.mc117)
}

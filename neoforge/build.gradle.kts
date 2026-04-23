plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    compileOnly(libs.jetbrains.annotations)

    // NeoForge SPI — provided at runtime by the NeoForge environment.
    // compileOnly("net.neoforged:neoforgespi:...") — wired in once ModDevGradle is configured at Phase 8.

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

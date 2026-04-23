plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    compileOnly(libs.jetbrains.annotations)

    // Fabric Loader API — provided at runtime by the Fabric environment.
    // compileOnly("net.fabricmc:fabric-loader:0.16.9") — wired in once Loom is configured at Phase 7.

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

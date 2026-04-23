plugins {
    `java-library`
}

repositories {
    maven("https://maven.neoforged.net/releases")
}

dependencies {
    api(project(":core"))
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.neoforge.spi)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testCompileOnly(libs.neoforge.spi)
}

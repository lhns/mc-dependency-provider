plugins {
    `java-library`
}

repositories {
    maven("https://maven.fabricmc.net/")
}

dependencies {
    api(project(":core"))
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.fabric.loader)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testCompileOnly(libs.fabric.loader)
}

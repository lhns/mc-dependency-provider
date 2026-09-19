plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

repositories {
    maven("https://maven.minecraftforge.net/")
}

// Forge 1.20.1 ships Java 17.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

val bundle by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // Forge SPI 7.x — Forge 1.20.x.
    compileOnly(libs.forge.spi.mc120)
    // fmlcore for Forge 1.20.1 — supplies net.minecraftforge.fml.ModContainer + lifecycle.
    compileOnly(libs.forge.fmlcore.mc120)
    compileOnly("net.minecraftforge:eventbus:6.0.5")
    compileOnly(libs.asm)
    compileOnly("org.apache.maven:maven-artifact:3.8.5")

    bundle(project(":core"))
    bundle(project(":deps-lib"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testCompileOnly(libs.forge.spi.mc120)
    testCompileOnly(libs.forge.fmlcore.mc120)
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("mcdp-forge-1.20")
    configurations = listOf(bundle)
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}

configurations.apply {
    named("apiElements").configure {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.named("shadowJar"))
    }
    named("runtimeElements").configure {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.named("shadowJar"))
    }
}

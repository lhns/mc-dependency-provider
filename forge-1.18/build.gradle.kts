plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

repositories {
    maven("https://maven.minecraftforge.net/")
}

// Forge 1.18 ships Java 17, so this subproject targets 17 (overrides the band-default
// of 21 set in the root subprojects {} block).
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))   // build JDK
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

    bundle(project(":core"))
    bundle(project(":deps-lib"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testCompileOnly(libs.forge.spi.mc118)
    testCompileOnly(libs.forge.fmlcore.mc118)
}

// ADR-0012 pattern (mirrors fabric/, neoforge/): produce a single shaded jar containing
// this subproject's classes plus :core and :deps-lib so the published mod jar is
// self-contained.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("mcdp-forge-1.18")
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

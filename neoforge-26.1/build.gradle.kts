import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

repositories {
    maven("https://maven.neoforged.net/releases")
    maven("https://libraries.minecraft.net/")
}

// NeoForge for MC 26.1.x — Mojang's calendar versioning era. JVM is Java 21+.
// Pinning to neoforgespi 9.0.2 for now (last released line); 26.1.x adapter may
// need a newer SPI version once NeoForge 26.x publishes a non-beta release with
// a separate SPI bump.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

// 26.1's NeoForge SPI may diverge from 21.x. Clone the 21.x source, adapt as needed.
// Default sourceSet config picks up neoforge-26.1/src/main/java/.

val bundle by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // fancymodloader:loader 4.0.42 ships the modernized IModLanguageLoader; standalone
    // neoforgespi:9.0.x has an outdated inner-class form. Mirror neoforge/'s pin (1.21 band)
    // until NeoForge 26.x publishes a non-beta SPI line that warrants per-band divergence.
    compileOnly(libs.neoforge.fml.loader)
    compileOnly(libs.neoforge.bus)

    bundle(project(":core"))
    bundle(project(":deps-lib"))
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("mcdp-neoforge-26.1")
    configurations = listOf(bundle)
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "FMLModType" to "LIBRARY",
            "Automatic-Module-Name" to "mcdepprovider"
        )
    }
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

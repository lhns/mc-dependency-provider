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

// The NeoForge SPI has not in fact diverged between 21.x and 26.1, so the adapter
// source is shared with the 21.x band (neoforge/). Only this band's resources —
// the META-INF service file — live here. When the SPI does diverge, split the
// source tree back out into neoforge-26.1/src/main/java/.
sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("neoforge/src/main/java")))
        resources.setSrcDirs(listOf("src/main/resources"))
    }
}

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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

repositories {
    maven("https://maven.neoforged.net/releases")
    maven("https://libraries.minecraft.net/")
}

// NeoForge for MC 1.20.6 — first JPMS-era band. NeoForge SPI 8.0.x.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

// NeoForge 20.6.x SPI (8.0.x) — adapter ported from neoforge/ (21.x) with two
// 8.0.x-specific tweaks documented inline in McdpLanguageLoader.java:
//   1. info.getLoader().name() (9.0.x-only) → walk getOwningFile().getFile().getLoaders()
//   2. ModFileScanData.getAnnotatedBy() (9.0.x-only) → filter getAnnotations() by descriptor
// The default sourceSet config picks up neoforge-1.20.6/src/main/java/.

val bundle by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // NeoForge 20.6.x SPI line + matching FML loader (provides ModContainer, LogUtils,
    // LoadingModList, etc.). Pin to a 20.6.x-compatible fancymodloader version when 20.6.x
    // adapter implementation actually iterates against runServer.
    compileOnly(libs.neoforge.spi.mc1206)
    compileOnly(libs.neoforge.fml.loader.mc1206)
    compileOnly(libs.neoforge.bus)

    bundle(project(":core"))
    bundle(project(":deps-lib"))
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("mcdp-neoforge-1.20.6")
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

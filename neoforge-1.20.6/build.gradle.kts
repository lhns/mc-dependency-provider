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

// NeoForge 20.6.x SPI uses different class names (e.g. ModContainer is at a different path)
// than the 21.x line we have in neoforge/. Source for this band is its own copy when the
// adapter lands; for the scaffold, the subproject is empty and the shadowJar bundles only
// core/+deps-lib so consumers can verify the artifact resolves end-to-end.
sourceSets {
    main {
        java.setSrcDirs(listOf<Any>())
        resources.setSrcDirs(listOf<Any>())
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
    // NeoForge 20.6.x SPI line. SPI surface for IModLanguageLoader, ModFileScanData, IModInfo
    // matches the 21.x line for the methods mcdp depends on.
    compileOnly(libs.neoforge.spi.mc1206)
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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.shadow)
}

repositories {
    maven("https://maven.neoforged.net/releases")
    // Mojang's repo — fancymodloader:loader pulls transitive com.mojang:logging from here.
    maven("https://libraries.minecraft.net/")
}

val bundle by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // Standalone neoforgespi:9.0.2 ships an outdated `IModLanguageProvider$IModLanguageLoader`
    // inner class; fancymodloader:loader:4.0.31+ re-ships a modernized top-level
    // `net.neoforged.neoforgespi.language.IModLanguageLoader`. Pin only the loader artifact
    // on compile so we bind against the FML 4.0.x shape that matches NeoForge 21.1.x runtime.
    compileOnly(libs.neoforge.fml.loader)
    compileOnly(libs.neoforge.bus)

    bundle(project(":core"))
    bundle(project(":deps-lib"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":core"))
    testImplementation(project(":deps-lib"))
    testImplementation(libs.neoforge.fml.loader)
    testImplementation(libs.neoforge.bus)
}

// ADR-0012 resolution: mirror :fabric's shadow-plugin recipe. Produce a single
// classifier-less jar bundling :core + :deps-lib + tomlj alongside the NeoForge
// adapter classes. Published to mavenLocal for test-mods/neoforge-example's
// additionalRuntimeClasspath wiring.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configurations = listOf(bundle)
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // FML 4.0.x (NeoForge 21.1.x) routes jars with FMLModType=LIBRARY into the
    // PLUGIN module layer, which is where it ServiceLoader-scans IModLanguageLoader.
    // LANGPROVIDER is not a valid value in this FML version; valid enum values are
    // MOD, LIBRARY, GAMELIBRARY (IModFile.Type). Without LIBRARY the jar is treated
    // as a regular mod and never reaches the plugin layer.
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifact(tasks.named("shadowJar"))
        }
    }
}

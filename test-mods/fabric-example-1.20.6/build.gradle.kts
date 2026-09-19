plugins {
    `java-library`
    // Loom 1.9-SNAPSHOT — matches the 1.21 band's pin (known-working on Gradle 8.11).
    // 1.10+ requires Gradle 8.12; 1.7's nightly had a Problems.forNamespace API
    // mismatch with 8.11. 1.9 is the sweet spot.
    id("fabric-loom") version "1.9-SNAPSHOT"
    id("de.lhns.mcdp")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

group = "com.example"
version = "0.1.0"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

val minecraftVersion = "1.20.6"
val yarnMappings = "1.20.6+build.3"
val loaderVersion = "0.15.11"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("de.lhns.mcdp:mcdp-1.20.6:0.1.0-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

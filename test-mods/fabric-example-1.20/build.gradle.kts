plugins {
    `java-library`
    // Loom 1.4.x is the recommended pin for MC 1.20.1 (Java 17 era).
    id("fabric-loom") version "1.4-SNAPSHOT"
    id("de.lhns.mcdp")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val minecraftVersion = "1.20.1"
val yarnMappings = "1.20.1+build.10"
val loaderVersion = "0.15.11"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")

    // mcdp-1.20 — composite-substituted to :mcdp-1.20 (Fabric + Forge for 1.20.1).
    modImplementation("de.lhns.mcdp:mcdp-1.20:0.1.0-SNAPSHOT")

    // Mod's own Maven dep, served by mcdp at runtime.
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider {
    lang.set("java")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

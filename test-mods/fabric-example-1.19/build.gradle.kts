plugins {
    `java-library`
    // Same Loom pin as the other Java-17-era bands; Loom 1.9 still resolves 1.19.2.
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

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val minecraftVersion = "1.19.2"
val yarnMappings = "1.19.2+build.28"
// 0.14.x is the loader line that shipped during 1.19; matches the band's fabric.mod.json floor.
val loaderVersion = "0.14.25"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")

    // mcdp-1.19 — composite-substituted to :mcdp-1.19 (Fabric + Forge for 1.19.2).
    modImplementation("de.lhns.mcdp:mcdp-1.19:0.1.0-SNAPSHOT")

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

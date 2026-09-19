plugins {
    `java-library`
    // Loom 1.0.x for MC 1.18.2 (Java 17 era).
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

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

val minecraftVersion = "1.18.2"
val yarnMappings = "1.18.2+build.4"
val loaderVersion = "0.14.22"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("de.lhns.mcdp:mcdp-1.18:0.1.0-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

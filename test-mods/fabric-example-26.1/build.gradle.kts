plugins {
    `java-library`
    // Loom version pinned for MC 26.1.x. Latest Loom (~1.10/2.x) handles the new
    // calendar-versioning scheme. Adjust if a more current Loom release is published.
    id("fabric-loom") version "1.10-SNAPSHOT"
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

// Mojang's calendar versioning. yarn-mappings name format may differ from the 1.x
// line — adjust based on whatever yarn ships for 26.1.x.
val minecraftVersion = "26.1.2"
val yarnMappings = "26.1.2+build.1"
val loaderVersion = "0.16.9"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("de.lhns.mcdp:mcdp-26.1:0.1.0-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

plugins {
    `java-library`
    // Loom 1.9 is the newest line usable on the repo's Gradle 8.11.1 wrapper (1.10+ needs
    // 8.12 — see fabric-example-1.20.6). It cannot read 1.21.11's yarn unpick metadata
    // ("Unsupported unpick version"), so this band uses Mojang's official mappings instead:
    // the mod references no Minecraft types, so yarn buys it nothing.
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

// Straight off fabric-meta: /v2/versions/loader/1.21.11 -> loader 0.19.5 (stable).
val minecraftVersion = "1.21.11"
val loaderVersion = "0.19.5"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("de.lhns.mcdp:mcdp-1.21.11:0.1.0-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

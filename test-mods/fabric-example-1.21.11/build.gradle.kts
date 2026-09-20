plugins {
    `java-library`
    // NOTE (ADR-0030): this pin is the one unverified piece of the 1.21.11 band.
    // The repo wrapper is Gradle 8.11.1 and CI drives test mods through `../../gradlew`, so the
    // newest Loom usable here is the 1.9 line (1.10+ requires Gradle 8.12 — see the comment in
    // fabric-example-1.20.6/build.gradle.kts). Whether Loom 1.9 can provision MC 1.21.11 has NOT
    // been confirmed; if it cannot, the fix is a root wrapper bump to >= 8.12 plus a current
    // Loom (1.18.2 is the newest on maven.fabricmc.net), or a committed per-mod wrapper in this
    // directory the way forge-example-1.18 carries its own. Until that is settled this mod is
    // deliberately NOT in the CI smoke matrix.
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

// Straight off fabric-meta: /v2/versions/loader/1.21.11 -> loader 0.19.5 (stable),
// /v2/versions/yarn/1.21.11 -> 1.21.11+build.6 (stable).
val minecraftVersion = "1.21.11"
val yarnMappings = "1.21.11+build.6"
val loaderVersion = "0.19.5"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("de.lhns.mcdp:mcdp-1.21.11:0.1.0-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

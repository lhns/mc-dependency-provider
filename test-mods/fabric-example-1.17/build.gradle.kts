plugins {
    `java-library`
    // Loom 0.10.x is the recommended pin for MC 1.17.x (Java 16 era).
    // Loom 0.10 may require Gradle 7.x — set the wrapper accordingly per subproject.
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

java { toolchain { languageVersion.set(JavaLanguageVersion.of(16)) } }

val minecraftVersion = "1.17.1"
val yarnMappings = "1.17.1+build.65"
val loaderVersion = "0.14.21"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("de.lhns.mcdp:mcdp-1.17:0.1.0-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

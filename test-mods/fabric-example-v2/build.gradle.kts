// Second Fabric test-mod for the version-isolation smoke (task #22).
//
// Pins cats-core_3:2.10.0 — a DIFFERENT SHA from fabric-example's 2.13.0. When both
// mods boot together, SHA-keyed classloader coalescing (ADR-0006) gives each mod its
// own cats-core classloader. The onLoad() output for each mod prints the identity
// hashcodes of `cats.Functor.class` and its classloader; a CI assertion then greps
// the server log for two distinct lines and confirms they differ.

plugins {
    scala
    id("fabric-loom") version "1.9-SNAPSHOT"
    id("io.github.mclibprovider")
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
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val minecraftVersion = "1.21.1"
val yarnMappings = "1.21.1+build.3"
val loaderVersion = "0.16.9"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")

    // The mc-lib-provider Fabric adapter (consumed as a shadow jar from mavenLocal,
    // same recipe as fabric-example). Provides the `mclibprovider` LanguageAdapter that
    // this mod's fabric.mod.json references.
    modImplementation("io.github.mclibprovider:fabric:0.1.0-SNAPSHOT")

    mcLibImplementation("org.scala-lang:scala3-library_3:3.5.2")
    // Deliberately different cats version from fabric-example so the two mods' cats
    // jars have distinct SHAs and resolve to distinct classloaders.
    mcLibImplementation("org.typelevel:cats-core_3:2.10.0")
}

mclibprovider {
    lang.set("scala")
    sharedPackages.add("com.example_v2.api")
}

loom {
    runs {
        named("client") { vmArgs("-Dfabric.development=true") }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

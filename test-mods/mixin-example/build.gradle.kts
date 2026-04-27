plugins {
    scala
    kotlin("jvm") version "2.1.0"
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

    // The unified mcdpprovider jar — composite-substituted to :mcdp.
    modImplementation("de.lhns.mcdp:mcdp:0.1.0-SNAPSHOT")

    // One representative mcdep so the manifest pipeline isn't a no-op. The Scala stdlib
    // anchors the per-mod loader (the Scala entrypoint adapter needs it at runtime).
    mcdepImplementation("org.scala-lang:scala3-library_3:3.5.2")
    mcdepImplementation("org.typelevel:cats-core_3:2.13.0")

    // Kotlin stdlib so the Kotlin-authored mod-private helper resolves at runtime under
    // the mod's per-mod ModClassLoader. Phase-4 coverage (ADR-0018): proves codegen
    // passes through kotlinc-emitted bytecode (Metadata annotation, INSTANCE-pattern
    // objects with @JvmStatic forwarders).
    mcdepImplementation(kotlin("stdlib"))
}

mcdepprovider {
    lang.set("scala")

    // No sharedPackages.add(...) here on purpose: the auto-codegen (ADR-0018) emits its bridge
    // package and adds it to sharedPackages itself. This mod is the integration smoke for that
    // auto-add wiring — manual entries would mask a regression.

    // Same Loom limitation as fabric-example: AbstractRunTask finalizes classpath before doFirst.
    patchRunTasks.set(emptyList<String>())
}

loom {
    runs {
        named("client") {
            vmArgs("-Dfabric.development=true")
        }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

plugins {
    `java-library`
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

    // The unified mcdpprovider jar — composite-substituted to :mcdp-1.21.
    modImplementation("de.lhns.mcdp:mcdp-1.21:0.2.1-SNAPSHOT")

    // One representative mcdep so the manifest pipeline isn't a no-op and the mod really
    // gets a per-mod ModClassLoader — without one there is no loader boundary for the
    // generated bridges to cross.
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider {
    // Java, not Scala, and that is the whole point of this mod: scalac chains a bridged call
    // straight into the next one and never binds a local, so a mixin compiled by scalac has no
    // LocalVariableTable entry naming the mod-private type. javac binds locals and emits one.
    lang.set("java")

    // The load-bearing line. Unlike every other test mod, the shared package here IS the
    // mixin package — the configuration mc-fluid-physics runs and no in-tree mod reproduced.
    // Validator A therefore scans classes that the ADR-0018 codegen has rewritten, which is
    // the only way its bridge-descriptor exemption (and the holes in it) get exercised.
    sharedPackages.add("com.example.sharedmixin.mixin")

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

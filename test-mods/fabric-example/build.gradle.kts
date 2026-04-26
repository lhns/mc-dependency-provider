plugins {
    scala
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

    // The mcdepprovider Fabric adapter — the actual mod with id "mcdepprovider"
    // that fabric_example depends on at runtime. Resolved via the composite build's
    // settings-level includeBuild substitution.
    modImplementation("de.lhns.mcdp:mcdp-fabric:0.1.0-SNAPSHOT")

    // Representative Scala-ecosystem deps — the motivating case for mcdepprovider.
    // mcdepImplementation is the opt-in bucket: deps placed here are emitted into
    // META-INF/mcdepprovider.toml, downloaded at runtime by the provider, and served
    // through a per-mod URLClassLoader. RunTaskClasspathPatch strips them from
    // runServer/runClient so dev-mode parity with production is maintained (ADR-0007).
    mcdepImplementation("org.scala-lang:scala3-library_3:3.5.2")
    mcdepImplementation("org.typelevel:cats-core_3:2.13.0")
    mcdepImplementation("io.circe:circe-core_3:0.14.10")
    mcdepImplementation("io.circe:circe-parser_3:0.14.10")

    // @McdpMixin + McdpProvider classes are shaded into the fabric shadow jar
    // (ADR-0012), so modImplementation above provides them at both compile and runtime.
}

mcdepprovider {
    lang.set("scala")
    sharedPackages.add("com.example.api")

    // Disabled: Loom's AbstractRunTask finalizes getClasspath() before doFirst actions can
    // mutate it, so setClasspath throws IllegalStateException. Tracked separately —
    // dev-mode parity on Fabric needs a different hook (likely a Configuration-level
    // dependency-resolution substitution rather than a JavaExec doFirst).
    patchRunTasks.set(emptyList<String>())
}

loom {
    runs {
        named("client") {
            // Keep dev runs headless-friendly for CI.
            vmArgs("-Dfabric.development=true")
        }
    }
}
// NOTE (dev-mode blocker, documented in ADR-0012):
// Fabric's ClasspathModCandidateFinder refuses to expose classes from sibling
// composite-build jars (core, deps-lib) to the mcdepprovider mod. Setting
// -Dfabric.classPathGroups via Loom's runs { property } DSL did not take
// effect in local testing (neither classes-dirs nor jar paths matched Fabric's
// URL comparison). Production deploys ship a single shaded jar and avoid
// this entirely; CI Tier-2 on Linux is the load-bearing validation. Deferred.

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

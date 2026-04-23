plugins {
    scala
    id("fabric-loom") version "1.9-SNAPSHOT"
    id("io.github.mclibprovider")
}

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Pin platform versions — MC 1.21.1, Fabric Loader 0.16.x, official Mojang mappings.
// These are provided by the platform at runtime, so mc-lib-provider's manifest task
// excludes them (see `excludeGroup` lines below).
val minecraftVersion = "1.21.1"
val yarnMappings = "1.21.1+build.3"
val loaderVersion = "0.16.9"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")

    // mc-lib-provider itself is a mod dependency at runtime. For now it's published into
    // the composite build, so Loom picks it up via the includeBuild. In a published world
    // this would be `modImplementation("io.github.mclibprovider:fabric:<ver>")`.

    // Representative Scala-ecosystem deps — the motivating case for mc-lib-provider.
    // These are resolved through mc-lib-provider's manifest + per-mod classloader path,
    // NOT the normal Minecraft classpath. The RunTaskClasspathPatch strips them from
    // runServer/runClient so dev-mode parity with production is maintained (ADR-0007).
    implementation("org.scala-lang:scala3-library_3:3.5.2")
    implementation("org.typelevel:cats-core_3:2.13.0")
    implementation("io.circe:circe-core_3:0.14.10")
    implementation("io.circe:circe-parser_3:0.14.10")
}

mclibprovider {
    lang.set("scala")
    sharedPackages.add("com.example.api")
    // Platforms provide these at runtime — never bundle them.
    excludeGroup("net.minecraft")
    excludeGroup("net.neoforged")
    excludeGroup("net.fabricmc")
    excludeGroup("com.mojang")
    // Loom adds these compile-only helpers; they don't belong in the mod's runtime manifest.
    excludeGroup("org.ow2.asm")
    excludeGroup("org.spongepowered")
}

loom {
    runs {
        named("client") {
            // Keep dev runs headless-friendly for CI.
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

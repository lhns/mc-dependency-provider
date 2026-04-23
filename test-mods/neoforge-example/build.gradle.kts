plugins {
    scala
    id("net.neoforged.moddev") version "2.0.78"
    id("io.github.mclibprovider")
}

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

neoForge {
    // NeoForge 21.1.x targets Minecraft 1.21.1. 21.1.77 is a 1.21.1 stable line.
    // Keep this pinned — CI Tier-2 (#29) asserts we boot this exact version.
    version = "21.1.77"

    runs {
        create("client") { client() }
        create("server") { server() }
    }

    mods {
        create("neoforge_example") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    // Representative Scala-ecosystem deps — the motivating case for mc-lib-provider.
    // These are served through mc-lib-provider's manifest + per-mod classloader,
    // NOT the normal NeoForge classpath. RunTaskClasspathPatch strips them from
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
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to project.version)
    }
}

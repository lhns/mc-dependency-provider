plugins {
    scala
    id("net.neoforged.moddev") version "2.0.78"
    id("de.lhns.mcdp")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.neoforged.net/releases/")
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
    // ADR-0012: the mcdepprovider NeoForge adapter, consumed as a single shadow jar
    // from mavenLocal. Lands on the sourceSet runtimeClasspath which MDG 2.0.78 feeds
    // to runServer/runClient; FML discovers it on java.class.path via its bundled
    // META-INF/neoforge.mods.toml and picks up the IModLanguageProvider via
    // META-INF/services. (The `additionalRuntimeClasspath` configuration is for
    // non-mod libs that FML would otherwise mis-scan — not for actual mod jars.)
    implementation("de.lhns.mcdp:neoforge:0.1.0-SNAPSHOT")

    // Representative Scala-ecosystem deps — the motivating case for mcdepprovider.
    // mcdepImplementation: opt-in bucket served through the per-mod ModClassLoader.
    // RunTaskClasspathPatch strips them from runServer/runClient so dev-mode parity
    // with production is maintained (ADR-0007).
    mcdepImplementation("org.scala-lang:scala3-library_3:3.5.2")
    mcdepImplementation("org.typelevel:cats-core_3:2.13.0")
    mcdepImplementation("io.circe:circe-core_3:0.14.10")
    mcdepImplementation("io.circe:circe-parser_3:0.14.10")
}

mcdepprovider {
    lang.set("scala")
    sharedPackages.add("com.example.api")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to project.version)
    }
}

plugins {
    `java-library`
    // ModDevGradle pin for NeoForge 26.x. The 26.x line is currently in beta; pin the
    // matching MDG version (or use 2.0.78+ which usually tracks NeoForge releases).
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

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

neoForge {
    // Pin a 26.1.x NeoForge build. As of writing the line is beta-only.
    version = "26.1.2.41-beta"

    runs {
        create("server") { server() }
    }

    mods {
        create("neoforge_example_261") { sourceSet(sourceSets.main.get()) }
    }
}

dependencies {
    implementation("de.lhns.mcdp:mcdp-26.1:0.1.0-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") { expand("version" to project.version) }
}

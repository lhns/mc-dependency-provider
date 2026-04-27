plugins {
    kotlin("jvm") version "2.1.0"
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
    version = "21.1.77"

    runs {
        create("client") { client() }
        create("server") { server() }
    }

    mods {
        create("kotlin_example") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    implementation("de.lhns.mcdp:mcdp:0.1.0-SNAPSHOT")

    mcdepImplementation(kotlin("stdlib"))
    mcdepImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

mcdepprovider {
    lang.set("kotlin")
    sharedPackages.add("com.example.api")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to project.version)
    }
}

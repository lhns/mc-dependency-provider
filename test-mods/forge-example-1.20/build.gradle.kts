// ForgeGradle 6.x supports MC 1.20.1 + Gradle 8 (the 1.18-era 5.1.x line is for older
// MC). This subproject can run on the parent's Gradle 8.x — no wrapper override needed.
buildscript {
    repositories {
        maven("https://maven.minecraftforge.net/")
        mavenCentral()
    }
    dependencies {
        classpath("net.minecraftforge.gradle:ForgeGradle:6.0.+") { isChanging = true }
    }
}

plugins {
    `java-library`
    id("de.lhns.mcdp")
}

apply(plugin = "net.minecraftforge.gradle")

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories { mavenCentral() }

configure<net.minecraftforge.gradle.userdev.UserDevExtension> {
    mappings("official", "1.20.1")
    runs {
        create("server") {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "info")
            mods {
                create("forge_example_120") { source(sourceSets.main.get()) }
            }
        }
    }
}

dependencies {
    "minecraft"("net.minecraftforge:forge:1.20.1-47.4.20")
    implementation("de.lhns.mcdp:mcdp-1.20:0.1.0-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

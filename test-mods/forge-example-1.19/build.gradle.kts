// ForgeGradle 6.x supports MC 1.19.2 on Gradle 8 — the official `forge-1.19.2-43.5.2-mdk.zip`
// declares `id 'net.minecraftforge.gradle' version '[6.0,6.2)'` with a Gradle 8.8 wrapper. So,
// like forge-example-1.20 and unlike forge-example-1.18, this subproject runs on the parent's
// Gradle 8.x: no wrapper override, and `includeBuild("../..")` stays.
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
    mappings("official", "1.19.2")
    runs {
        create("server") {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "info")
            mods {
                create("forge_example_119") { source(sourceSets.main.get()) }
            }
        }
    }
}

dependencies {
    "minecraft"("net.minecraftforge:forge:1.19.2-43.5.2")
    implementation("de.lhns.mcdp:mcdp-1.19:0.1.0-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

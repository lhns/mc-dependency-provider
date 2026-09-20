// ForgeGradle 5.1.x rejects Gradle 8.x; use the local Gradle 7.6 wrapper.
buildscript {
    repositories {
        maven("https://maven.minecraftforge.net/")
        mavenCentral()
    }
    dependencies {
        classpath("net.minecraftforge.gradle:ForgeGradle:5.1.+") { isChanging = true }
    }
}

plugins {
    `java-library`
    id("de.lhns.mcdp") version "0.1.0-SNAPSHOT"
}

apply(plugin = "net.minecraftforge.gradle")

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(16))
    }
}

// mavenLocal FIRST: no includeBuild("../.."), so `de.lhns.mcdp:mcdp-1.17:0.1.0-SNAPSHOT`
// only exists in the parent build's publishToMavenLocal output. settings.gradle.kts has
// mavenLocal under `pluginManagement` only, which does not cover dependency resolution.
repositories {
    mavenLocal()
    mavenCentral()
}

// SNAPSHOT dependencies are "changing" modules, which Gradle caches for 24h by default.
// That defeats the CI preflight: `publishToMavenLocal` writes a fresh mcdp jar, the
// consumer build resolves the previous one out of ~/.gradle/caches/modules-2, and the
// build fails against stale bytecode (seen as ASM "Unsupported class file major version"
// when the plugin's target changed). Re-resolve every build instead -- these test mods
// exist to exercise whatever was just published.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

configure<net.minecraftforge.gradle.userdev.UserDevExtension> {
    mappings("official", "1.17.1")
    runs {
        create("server") {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "info")
            mods {
                create("forge_example_117") { source(sourceSets.main.get()) }
            }
        }
    }
}

dependencies {
    "minecraft"("net.minecraftforge:forge:1.17.1-37.1.2")
    implementation("de.lhns.mcdp:mcdp-1.17:0.1.0-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

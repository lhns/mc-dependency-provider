// ForgeGradle 5.1.x for MC 1.18.2 does NOT support Gradle 8.x — rejects with
// EnvironmentChecks.checkGradleRange. Run this test mod with a Gradle 7.5.x wrapper
// (override gradle/wrapper/gradle-wrapper.properties for THIS subproject only;
// the parent mc-scala build is on 8.x and stays there).
//
// ForgeGradle is a Groovy-DSL plugin without a maintained Kotlin-DSL surface.
// Apply it via the buildscript classpath block (matches fluidphysics's forge-1.18 setup).
buildscript {
    repositories {
        maven("https://maven.minecraftforge.net/")
        mavenCentral()
    }
    dependencies {
        classpath("net.minecraftforge.gradle:ForgeGradle:5.1.+") {
            isChanging = true
        }
    }
}

plugins {
    `java-library`
    // Without composite-include we need an explicit version. The mcdp gradle-plugin
    // is published via the parent's `:gradle-plugin:publishToMavenLocal` to match this
    // pin, OR resolved from Sonatype snapshots for un-modified branches.
    id("de.lhns.mcdp") version "0.1.0-SNAPSHOT"
}

apply(plugin = "net.minecraftforge.gradle")

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

// ForgeGradle's `minecraft { ... }` block configures MC version + mappings + run tasks.
// The Kotlin-DSL surface uses `the<...>()` lookup to access the Groovy-typed extension.
configure<net.minecraftforge.gradle.userdev.UserDevExtension> {
    mappings("official", "1.18.2")
    runs {
        create("server") {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "info")
            mods {
                create("forge_example_118") {
                    source(sourceSets.main.get())
                }
            }
        }
    }
}

dependencies {
    "minecraft"("net.minecraftforge:forge:1.18.2-40.3.12")

    // mcdpprovider runtime jar — composite-substituted to :mcdp-1.18 by mc-scala root.
    implementation("de.lhns.mcdp:mcdp-1.18:0.1.0-SNAPSHOT")

    // Mod's own Maven dep, served by mcdp at runtime.
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider {
    lang.set("java")
}

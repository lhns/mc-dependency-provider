rootProject.name = "forge-example-1.18"

// An ordinary composite-included test mod, exactly like forge-example-1.20.
//
// This used to be a standalone build with its own Gradle 7.6 wrapper, on the premise that
// "ForgeGradle 5.1 is the only FG line for MC <= 1.18". That premise is false: Forge
// regenerated the 1.18.2 MDK onto FG6 — `forge-1.18.2-40.3.12-mdk.zip` declares
// `id 'net.minecraftforge.gradle' version '[6.0,6.2)'` and ships a Gradle 8.8 wrapper (the
// 1.16.5 MDK is FG6 / Gradle 8.4 too, so FG6 handles userdev configs on both sides of 1.17).
// FG 6.0.54's EnvironmentChecks.checkEnvironment accepts Gradle [8.1, 9.0), and the repo-root
// wrapper is 8.11.1 — inside that window. So there is nothing to isolate: build this mod with
// `../../gradlew` like every other one.

pluginManagement {
    plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0" }
    includeBuild("../..")
    repositories {
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") }

includeBuild("../..")

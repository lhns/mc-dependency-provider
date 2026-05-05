rootProject.name = "fabric-example-1.20.6"

pluginManagement {
    plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0" }
    includeBuild("../..")
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") }

includeBuild("../..")

dependencyResolutionManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenLocal()
        mavenCentral()
    }
}

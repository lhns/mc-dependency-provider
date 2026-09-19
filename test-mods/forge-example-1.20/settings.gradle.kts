rootProject.name = "forge-example-1.20"

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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}

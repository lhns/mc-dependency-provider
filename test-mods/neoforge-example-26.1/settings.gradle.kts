rootProject.name = "neoforge-example-26.1"

pluginManagement {
    includeBuild("../..")
    repositories {
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        gradlePluginPortal()
        mavenCentral()
    }
}

includeBuild("../..")

dependencyResolutionManagement {
    repositories {
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        mavenLocal()
        mavenCentral()
    }
}

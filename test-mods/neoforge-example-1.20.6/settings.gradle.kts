rootProject.name = "neoforge-example-1.20.6"

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

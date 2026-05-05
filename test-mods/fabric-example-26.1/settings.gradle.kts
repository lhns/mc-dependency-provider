rootProject.name = "fabric-example-26.1"

pluginManagement {
    includeBuild("../..")
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

includeBuild("../..")

dependencyResolutionManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenLocal()
        mavenCentral()
    }
}

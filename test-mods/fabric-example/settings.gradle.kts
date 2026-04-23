rootProject.name = "fabric-example"

pluginManagement {
    // Composite-build the whole mc-lib-provider repo so local changes apply without
    // any publish step. The :gradle-plugin subproject supplies the mc-lib-provider plugin.
    includeBuild("../..")

    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
    }
}

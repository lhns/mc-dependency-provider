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

// Second includeBuild at settings level so regular library artifacts (e.g.
// io.github.mclibprovider:core for the mixin bridge's @McLibMixin annotation)
// are substituted too, not only Gradle plugins.
includeBuild("../..")

dependencyResolutionManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
    }
}

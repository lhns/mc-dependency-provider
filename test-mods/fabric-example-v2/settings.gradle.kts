rootProject.name = "fabric-example-v2"

pluginManagement {
    includeBuild("../..")
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

// Top-level includeBuild for module substitution (auto-substitutes
// `de.lhns.mcdp:mcdp:VERSION` to project `:mcdp`). See kotlin-example/
// settings.gradle.kts for the full rationale.
includeBuild("../..")

dependencyResolutionManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "scala-example"

pluginManagement {
    // Composite-build the mcdepprovider repo for plugin resolution.
    includeBuild("../..")

    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Top-level includeBuild for module substitution. See kotlin-example/
// settings.gradle.kts for the full rationale.
includeBuild("../..")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

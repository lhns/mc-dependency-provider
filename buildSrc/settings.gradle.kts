// buildSrc is its own build; it does not inherit the outer build's repositories or
// version catalog. Re-point at the same catalog file so plugin versions are pinned in
// exactly one place (gradle/libs.versions.toml).
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

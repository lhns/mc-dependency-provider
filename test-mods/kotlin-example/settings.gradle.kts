rootProject.name = "kotlin-example"

pluginManagement {
    // Composite-build the mcdepprovider repo for plugin resolution
    // (the `de.lhns.mcdp` Gradle plugin lives in :gradle-plugin).
    includeBuild("../..")

    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Top-level includeBuild enables MODULE substitution from the same composite
// build. The `:mcdp` project name matches the published artifactId, so
// auto-substitution maps `de.lhns.mcdp:mcdp:VERSION` to it without any
// explicit substitution rule. (pluginManagement.includeBuild contributes
// only plugins, not modules — both calls are needed.)
includeBuild("../..")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

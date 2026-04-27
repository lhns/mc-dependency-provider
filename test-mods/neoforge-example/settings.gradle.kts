rootProject.name = "neoforge-example"

pluginManagement {
    // Composite-build the mcdepprovider repo for plugin resolution.
    includeBuild("../..")

    repositories {
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        gradlePluginPortal()
        mavenCentral()
    }
}

// Top-level includeBuild enables MODULE substitution from the same composite
// build. The `:mcdp` project name matches the published artifactId, so
// auto-substitution maps `de.lhns.mcdp:mcdp:VERSION` to it without any
// explicit substitution rule. pluginManagement.includeBuild above contributes
// only plugins; top-level here contributes modules.
includeBuild("../..")

dependencyResolutionManagement {
    repositories {
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "mcdepprovider"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.fabricmc.net/")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.fabricmc.net/")
    }
}

include("deps-lib")
include("core")
include("gradle-plugin")
include("fabric")
include("neoforge")
include("mcdp")
// Project path stays `:mcdp` so composite-build auto-substitution maps `de.lhns.mcdp:mcdp:VERSION`
// to it (the project name must match the artifactId). On disk the merged-jar module lives under
// `multi/` to describe its purpose (multi-loader bundle of fabric+neoforge).
project(":mcdp").projectDir = file("multi")
include("cli")

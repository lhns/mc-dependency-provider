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
// Per-MC-band adapter subprojects. The project name encodes the band (e.g. `:fabric-1.21`)
// because the composite-build auto-substitution maps `de.lhns.mcdp:<artifactId>:VERSION` to
// the project whose name matches `<artifactId>`. Each band publishes as `mcdp-<band>` so
// consumers pin the band they target.
include("fabric-1.21")
project(":fabric-1.21").projectDir = file("fabric")
include("neoforge-1.21")
project(":neoforge-1.21").projectDir = file("neoforge")
include("mcdp-1.21")
project(":mcdp-1.21").projectDir = file("multi")

// Forge 1.18 — scaffold (Java 17 target, forgespi 4.0.x). Adapter body not yet
// implemented; see forge-1.18/src/.../McdpLanguageProvider.java.
include("forge-1.18")

include("cli")

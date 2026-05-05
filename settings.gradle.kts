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

// Forge bands — scaffold subprojects (adapter body not yet implemented in any of these;
// see forge-1.18/src/.../McdpLanguageProvider.java for the roadmap).
//   forge-1.17: Java 16, forgespi 3.2.x
//   forge-1.18: Java 17, forgespi 4.0.x
//   forge-1.20: Java 17, forgespi 7.x  (1.20.1)
include("forge-1.17")
include("forge-1.18")
include("forge-1.20")

include("cli")

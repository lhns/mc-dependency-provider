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

// Fabric per-band subprojects — share source with fabric/ (the 1.21 band) via srcDirs;
// only the bytecode target differs (1.17 = Java 16; 1.18, 1.20 = Java 17). The Fabric
// LanguageAdapter + PreLaunchEntrypoint surface is stable across fabric-loader 0.14+.
include("fabric-1.17")
include("fabric-1.18")
include("fabric-1.20")

// Multi-loader aggregators per band — bundle the band's fabric-X + (forge-X | neoforge-X)
// shadowJars into one runtime artifact published as `de.lhns.mcdp:mcdp-X`. Bands without
// NeoForge (≤ 1.20.4) bundle Fabric + Forge. NeoForge bands (1.20.6+) bundle Fabric +
// NeoForge (Forge is forked off as NeoForge starting 1.20.5).
include("mcdp-1.17")
project(":mcdp-1.17").projectDir = file("multi-1.17")
include("mcdp-1.18")
project(":mcdp-1.18").projectDir = file("multi-1.18")
include("mcdp-1.20")
project(":mcdp-1.20").projectDir = file("multi-1.20")

// 1.20.6 band — first JPMS-era NeoForge. Fabric + NeoForge.
include("fabric-1.20.6")
include("neoforge-1.20.6")
include("mcdp-1.20.6")
project(":mcdp-1.20.6").projectDir = file("multi-1.20.6")

// 26.1 band — Mojang calendar versioning era. Latest MC release.
include("fabric-26.1")
include("neoforge-26.1")
include("mcdp-26.1")
project(":mcdp-26.1").projectDir = file("multi-26.1")

include("cli")

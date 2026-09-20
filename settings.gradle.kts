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

// Forge bands. forge-1.18 carries the real adapter (McdpLanguageProvider +
// McdpModContainer); forge-1.17 and forge-1.20 both share that source via srcDirs and
// only pin their own forgespi/fmlcore coordinates. 1.17 shares it because Forge
// 1.17.1-37.1.2 runs forgespi 4.0.x, not the 3.2.x the band was originally pinned to
// (ADR-0029) -- so the 4.0 adapter is correct for it verbatim.
//   forge-1.17: Java 16, forgespi 4.0.x  (1.17.1)
//   forge-1.18: Java 17, forgespi 4.0.x
//   forge-1.19: Java 17, forgespi 6.0.x  (1.19.2)
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

// 1.19 band. Fabric + Forge, Java 17. forgespi 6.0.0 sits between the 4.0 (1.17/1.18) and
// 7.x (1.20) surfaces the shared forge-1.18 adapter already spans, so it shares that source
// too -- verified by compiling it against forgespi 6.0.0 and 6.0.2. ADR-0031.
include("fabric-1.19")
include("forge-1.19")
include("mcdp-1.19")
project(":mcdp-1.19").projectDir = file("multi-1.19")

// 1.20.6 band — first JPMS-era NeoForge. Fabric + NeoForge.
include("fabric-1.20.6")
include("neoforge-1.20.6")
include("mcdp-1.20.6")
project(":mcdp-1.20.6").projectDir = file("multi-1.20.6")

// 1.21.11 band — really the FML-10 band: covers MC 1.21.10 + 1.21.11. NeoForge's SPI
// broke at fancymodloader 9.0 -> 10.0 (IModFile.findResource removed, SecureJar replaced
// by JarContents; FMLEnvironment.dist -> getDist()), so this band needs its own NeoForge
// source rather than sharing neoforge/'s 21.x tree. Fabric's surface is unchanged, so its
// half still shares fabric/. mcdp-1.21 stays 1.21.1-only. ADR-0030.
include("fabric-1.21.11")
include("neoforge-1.21.11")
include("mcdp-1.21.11")
project(":mcdp-1.21.11").projectDir = file("multi-1.21.11")

// 26 band — Mojang's calendar-versioning line (26.1, 26.2, 26.3, ...) as a SINGLE band.
// The SPI is byte-identical across the whole line, so there is nothing for a per-release
// band to encode. Its NeoForge half shares the 1.21.11 FML-10 port, not neoforge/'s 21.x
// tree: real 26.x ships fancymodloader 11/12, which carry the FML-10 removals. ADR-0032.
include("fabric-26")
include("neoforge-26")
include("mcdp-26")
project(":mcdp-26").projectDir = file("multi-26")

include("cli")

// NeoForge test mod for the `mcdp-26` band.
//
// Why 26.2 and not 26.3: **there is no stable NeoForge for MC 26.3.** As of 2026-09-20 the
// 26.3 line on maven.neoforged.net is beta-only (26.3.0.0-beta … 26.3.0.7-beta), five days
// after the game shipped. The newest stable build in the calendar line is 26.2.0.88. This
// is a deliberate choice, not an oversight: a CI cell that boots a beta loader reports
// NeoForge's instability as mcdp breakage.
//
// That costs nothing in coverage. The SPI this adapter implements is byte-identical across
// the whole line — fancymodloader 11.0.16 (which 26.2.0.88 pulls) and 12.0.0 (which
// 26.3.0.7-beta pulls) ship the same `IModLanguageLoader`, `ModContainer`, `IModInfo` and
// `ModFileScanData` bytes. Booting 26.2 exercises exactly the 26.3 surface. Re-pin to
// 26.3.0.x the day it leaves beta; it is a one-line change. See ADR-0032.

plugins {
    `java-library`
    // MDG and NeoForge must be pinned together, not independently — ADR-0023's
    // "MDG ↔ NeoForge version pairing" finding. 2.0.147 is the current MDG release.
    id("net.neoforged.moddev") version "2.0.147"
    id("de.lhns.mcdp") version "0.2.1-SNAPSHOT"
}

repositories {
    // mavenLocal FIRST and non-optional — no includeBuild("../.."), so this is the only
    // source of `de.lhns.mcdp:mcdp-26:0.2.1-SNAPSHOT`.
    mavenLocal()
    mavenCentral()
    maven("https://maven.neoforged.net/releases/")
}

group = "com.example"
version = "0.1.0"

// MC 26.x requires a Java 25 runtime. The mcdp jar we consume is release-21 bytecode and
// loads fine on it — the toolchain has to be 25, the artifact does not.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

neoForge {
    version = "26.2.0.88"   // newest STABLE build of the 26.x line; see header

    runs {
        // Tier-3 runClient cell; same shape as test-mods/neoforge-example's client config.
        // Unproven like everything else in this band — no CI run has ever booted MC 26.x at
        // all, on either dist. See the mc-client-nightly.yml header.
        create("client") { client() }
        create("server") { server() }
    }

    mods {
        create("neoforge_example_262") { sourceSet(sourceSets.main.get()) }
    }
}

dependencies {
    implementation("de.lhns.mcdp:mcdp-26:0.2.1-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") { expand("version" to project.version) }
}

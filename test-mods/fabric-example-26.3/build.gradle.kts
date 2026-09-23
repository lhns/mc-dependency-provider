// Fabric test mod for MC 26.3 (released 2026-09-15), consuming the single `mcdp-26` band.
//
// Runs on this directory's OWN Gradle 9.7.1 wrapper + a JDK 25 daemon — see
// settings.gradle.kts for why it cannot be composite-included into the root build.

plugins {
    `java-library`
    // Loom 1.18.x is the line current with MC 26.3 (1.18.2 published 2026-09-16, one day
    // after the game). Its Gradle module metadata declares `org.gradle.jvm.version = 25`,
    // so it *requires* a JDK 25 daemon — which this band needs anyway.
    //
    // Loom 1.17.21 is the last line that still declares jvm.version 21. It does NOT help:
    // Loom separately refuses a MC version whose required Java exceeds the daemon JVM, and
    // MC 26.x requires 25. So the daemon must be 25 either way, and 1.18.2 is the newer
    // line. Loom 1.18.x also declares `org.gradle.plugin.api-version = 9.7.0`, which is
    // why this directory's wrapper pins 9.7.1 — a 9.6 consumer is rejected at variant
    // selection. Drop to 1.17.21 (plugin.api-version 9.5.0, jvm.version 21) only if
    // 1.18.x turns out to be unusable here for some other reason.
    id("fabric-loom") version "1.18.2"
    // No includeBuild("../..") here, so the plugin needs an explicit version. Populate it
    // with `../../gradlew :gradle-plugin:publishToMavenLocal`, or let it resolve from the
    // Sonatype snapshot repo listed in settings.gradle.kts.
    id("de.lhns.mcdp") version "0.2.1-SNAPSHOT"
}

repositories {
    // mavenLocal FIRST and non-optional: with no includeBuild("../.."), the only source of
    // `de.lhns.mcdp:mcdp-26:0.2.1-SNAPSHOT` is the parent build's publishToMavenLocal.
    mavenLocal()
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

group = "com.example"
version = "0.1.0"

// 25, not 21: MC 26.x's own runtime requirement. The mcdp jar we consume is release-21
// bytecode, which loads fine here — bytecode is forward-compatible; it is the *toolchain*
// that has to be 25, not the artifact.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

val minecraftVersion = "26.3"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    // Yarn, NOT used. `net.fabricmc:yarn` stops at 1.21.11 — there is no yarn build for any
    // 26.x version (meta.fabricmc.net/v2/versions/yarn/26.3 is an empty array), and the
    // calendar line publishes a single rolling `net.fabricmc:intermediary:0.0.0`. Official
    // MC 26.x ships DEOBFUSCATED — the 26.3 client jar has 10,737 real net/minecraft/ class
    // names and zero obfuscated ones, where 1.21.11 has 10,201 obfuscated. So Mojang publishes
    // no client_mappings/server_mappings for the line and yarn has no 26.x builds: there is
    // nothing left to map. `net.fabricmc:intermediary:0.0.0` is Fabric's identity mappings
    // artifact for exactly this case, and fabric-meta returns it for every 26.x version.
    mappings("net.fabricmc:intermediary:0.0.0:v2")
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
    // `implementation`, not `modImplementation`, and that is a consequence of the line above:
    // Loom remaps every `modImplementation` dependency, including its sources jar, and source
    // remapping needs a "named" namespace that the identity intermediary does not have
    // ("Could not find namespace \"named\" in provided tiny tree"). Nothing here needs remapping
    // anyway — MC 26.x is already deobfuscated — and Fabric's ClasspathModCandidateFinder
    // discovers mcdp from the plain classpath in a dev run.
    implementation("de.lhns.mcdp:mcdp-26:0.2.1-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

// SNAPSHOT dependencies are "changing" modules, cached for 24h by default. That defeats the
// CI preflight: publishToMavenLocal writes a fresh mcdp jar and the consumer resolves the
// previous one. Re-resolve every build — this mod exists to exercise what was just built.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

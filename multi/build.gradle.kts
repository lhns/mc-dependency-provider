plugins {
    id("mcdp.band-aggregator")
}

// The 1.21-band aggregator: Gradle path `:mcdp-1.21`, on-disk dir `multi/`, published as
// `de.lhns.mcdp:mcdp-1.21`. One of several sibling band aggregators (`multi-1.17/` ..
// `multi-26/`); publication is per-band, see ADR-0023.
//
// Both loaders' metadata files coexist in the one jar: `fabric.mod.json` at the jar root
// (Fabric reads it); the NeoForge side uses `META-INF/services/IModLanguageLoader` +
// `FMLModType=LIBRARY` for discovery (no `neoforge.mods.toml` since this is a LIBRARY-type
// provider, not a mod). Each loader ignores the other's metadata, and the unused entry
// classes never link because no live code path references them on the wrong platform.
//
// The project *name* matches the published artifactId so composite-build auto-substitution
// from consumer test-mods works without explicit dependencySubstitution rules; the
// *directory* is named for what the module does. settings.gradle.kts maps one to the other.
mcdpBand {
    javaRelease.set(21)
    fmlModType.set("LIBRARY")
    pomDescription.set("Multi-loader (Fabric + NeoForge) JVM-language mod provider with per-mod Maven dependency isolation.")
}

dependencies {
    // `:fabric-1.21` and `:neoforge-1.21` rewire their `apiElements`/`runtimeElements`
    // outgoing artifacts to point at their respective shadowJars (see ADR-0012), so this
    // `bundle` configuration receives those shaded jars and not the raw source-set `jar`
    // outputs. Each shaded jar already contains `:core` and `:deps-lib`; shadow's EXCLUDE
    // duplicates strategy drops the duplicates.
    bundle(project(":fabric-1.21"))
    bundle(project(":neoforge-1.21"))
}

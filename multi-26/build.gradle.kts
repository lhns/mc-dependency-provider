plugins {
    id("mcdp.band-aggregator")
}

// MC 26.x band aggregator — Mojang's calendar-versioning line (26.1, 26.2, 26.3, …) as a
// single band. ADR-0032 supersedes the `mcdp-26.1` scaffold ADR-0023 introduced.
mcdpBand {
    javaRelease.set(21)
    fmlModType.set("LIBRARY")
    pomDescription.set("Multi-loader (Fabric + NeoForge) JVM-language mod provider for the MC 26.x line.")
}

dependencies {
    bundle(project(":fabric-26"))
    bundle(project(":neoforge-26"))
}

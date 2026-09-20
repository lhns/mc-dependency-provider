plugins {
    id("mcdp.band-aggregator")
}

// MC 1.21.11 band aggregator — the tail of the 1.21 line (1.21.10 / 1.21.11, NeoForge 21.10.x /
// 21.11.x, fancymodloader 10.0.x). Distinct from `mcdp-1.21`, which covers 1.21.1 only: the
// NeoForge adapter had to be forked at FML 10.0 (ADR-0030). The Fabric half is the same source
// as every other Fabric band.
mcdpBand {
    javaRelease.set(21)
    fmlModType.set("LIBRARY")
    pomDescription.set("Multi-loader (Fabric + NeoForge) JVM-language mod provider for MC 1.21.11.")
}

dependencies {
    bundle(project(":fabric-1.21.11"))
    bundle(project(":neoforge-1.21.11"))
}

plugins {
    id("mcdp.band-aggregator")
}

// MC 1.17 band aggregator. Fabric + Forge — the NeoForge fork only starts at 1.20.5+.
//
// `fmlModType` was deliberately left unset here while forge-1.17 was a stub. It is now the
// real adapter — the same source the 1.18/1.20 bands compile, since Forge 1.17.1 runs
// forgespi 4.0.x (ADR-0029) — so the band is routed like the other Forge bands.
mcdpBand {
    javaRelease.set(16)
    fmlModType.set("LANGPROVIDER")
    pomDescription.set("Multi-loader (Fabric + Forge) JVM-language mod provider for MC 1.17.")
}

dependencies {
    bundle(project(":fabric-1.17"))
    bundle(project(":forge-1.17"))
}

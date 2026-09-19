plugins {
    id("mcdp.band-aggregator")
}

// MC 1.18 band aggregator. Fabric + Forge — the NeoForge fork only starts at 1.20.5+.
mcdpBand {
    javaRelease.set(17)
    fmlModType.set("LANGPROVIDER")
    pomDescription.set("Multi-loader (Fabric + Forge) JVM-language mod provider for MC 1.18.")
}

dependencies {
    bundle(project(":fabric-1.18"))
    bundle(project(":forge-1.18"))
}

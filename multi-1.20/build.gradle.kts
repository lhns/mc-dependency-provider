plugins {
    id("mcdp.band-aggregator")
}

// MC 1.20 band aggregator (1.20.1). Fabric + Forge — the NeoForge fork only starts at 1.20.5+.
mcdpBand {
    javaRelease.set(17)
    fmlModType.set("LANGPROVIDER")
    pomDescription.set("Multi-loader (Fabric + Forge) JVM-language mod provider for MC 1.20.")
}

dependencies {
    bundle(project(":fabric-1.20"))
    bundle(project(":forge-1.20"))
}

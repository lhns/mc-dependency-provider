plugins {
    id("mcdp.band-aggregator")
}

// MC 1.19 band aggregator (1.19.2). Fabric + Forge — the NeoForge fork only starts at 1.20.5+.
mcdpBand {
    javaRelease.set(17)
    fmlModType.set("LANGPROVIDER")
    pomDescription.set("Multi-loader (Fabric + Forge) JVM-language mod provider for MC 1.19.")
}

dependencies {
    bundle(project(":fabric-1.19"))
    bundle(project(":forge-1.19"))
}

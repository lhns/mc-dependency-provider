plugins {
    id("mcdp.band-aggregator")
}

// MC 1.17 band aggregator. Fabric + Forge — the NeoForge fork only starts at 1.20.5+.
mcdpBand {
    javaRelease.set(16)
    fmlModType.set("LANGPROVIDER")
    pomDescription.set("Multi-loader (Fabric + Forge) JVM-language mod provider for MC 1.17.")
}

dependencies {
    bundle(project(":fabric-1.17"))
    bundle(project(":forge-1.17"))
}

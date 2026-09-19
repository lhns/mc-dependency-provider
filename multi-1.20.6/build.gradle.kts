plugins {
    id("mcdp.band-aggregator")
}

// MC 1.20.6 band aggregator — first JPMS-era NeoForge.
mcdpBand {
    javaRelease.set(21)
    fmlModType.set("LIBRARY")
    pomDescription.set("Multi-loader (Fabric + NeoForge) JVM-language mod provider for MC 1.20.6.")
}

dependencies {
    bundle(project(":fabric-1.20.6"))
    bundle(project(":neoforge-1.20.6"))
}

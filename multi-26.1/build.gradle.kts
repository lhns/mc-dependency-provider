plugins {
    id("mcdp.band-aggregator")
}

// MC 26.1 band aggregator — Mojang's calendar versioning era.
mcdpBand {
    javaRelease.set(21)
    fmlModType.set("LIBRARY")
    pomDescription.set("Multi-loader (Fabric + NeoForge) JVM-language mod provider for MC 26.1.")
}

dependencies {
    bundle(project(":fabric-26.1"))
    bundle(project(":neoforge-26.1"))
}

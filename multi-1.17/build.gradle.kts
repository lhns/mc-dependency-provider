plugins {
    id("mcdp.band-aggregator")
}

// MC 1.17 band aggregator. Fabric + Forge — the NeoForge fork only starts at 1.20.5+.
//
// `fmlModType` is deliberately left unset here, unlike the 1.18/1.20 Forge bands: the
// forge-1.17 adapter is still a stub (`getFileVisitor()` throws), so a LANGPROVIDER hint
// would only make FML load a service that blows up. ADR-0023 §"FMLModType per-band".
mcdpBand {
    javaRelease.set(16)
    pomDescription.set("Multi-loader (Fabric + Forge) JVM-language mod provider for MC 1.17.")
}

dependencies {
    bundle(project(":fabric-1.17"))
    bundle(project(":forge-1.17"))
}

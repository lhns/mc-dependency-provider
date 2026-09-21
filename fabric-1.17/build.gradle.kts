plugins {
    id("mcdp.fabric-band")
}

// Fabric for MC 1.17.x.
mcdpBand {
    javaRelease.set(16)
    fabricLoaderVersion.set("0.14")
}

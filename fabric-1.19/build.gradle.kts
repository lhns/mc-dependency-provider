plugins {
    id("mcdp.fabric-band")
}

// Fabric for MC 1.19.x (1.19.2).
mcdpBand {
    javaRelease.set(17)
    // 0.14.x is the loader line that shipped throughout MC 1.19's life (0.15.0 only landed in
    // Dec 2023, after 1.19.4 was superseded). A 0.15 floor would reject every 1.19 install.
    fabricLoaderVersion.set("0.14")
}

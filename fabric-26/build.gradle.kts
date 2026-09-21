plugins {
    id("mcdp.fabric-band")
}

// Fabric for Mojang's calendar-versioning line (MC 26.1, 26.2, 26.3, …) — ONE band for the
// whole line, not one per release (ADR-0032): the Fabric SPI this adapter touches
// (`LanguageAdapter`, `PreLaunchEntrypoint`, `ModContainer`) is byte-identical between
// fabric-loader 0.16.9 (the 1.21 pin) and 0.19.5 (the 26.3 line), so this band shares
// fabric/'s source like every other Fabric band and differs only in its pins and floors.
mcdpBand {
    // 21, not 25, although MC 26.x ships a Java 25 runtime: this is the *bytecode target*,
    // and a release-21 class loads fine on a JVM 25. Targeting 25 would need a JDK 25
    // toolchain for the whole root build (we are on Gradle 8.11.1 / JDK 21). The
    // `depends.java` value it also feeds is a floor, so 21 is satisfied on a 26.x runtime.
    javaRelease.set(21)
    // 0.19 is the fabric-loader line contemporaneous with MC 26.3 and the one carrying the
    // calendar-version game provider. Too low a floor is a silent runtime failure; too high
    // only asks a consumer to update their loader.
    fabricLoaderVersion.set("0.19")
    // Compiled against the 26.x-era loader rather than the 1.21 pin. Safe on the JDK 21
    // toolchain: fabric-loader's api classes are still major 52 (Java 8) even at 0.19.5.
    fabricLoaderArtifact.set(libs.fabric.loader.mc26)
}

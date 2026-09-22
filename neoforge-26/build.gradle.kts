plugins {
    id("mcdp.neoforge-band")
}

// NeoForge for Mojang's calendar-versioning line (MC 26.1 / 26.2 / 26.3) — one band for the
// whole line (ADR-0032): every SPI member the adapter links against is unchanged from
// fancymodloader 10.0.36 (what this band compiles against, below) through 11.0.15
// (NeoForge 26.1.2.109), 11.0.16 (26.2.0.88) and 12.0.0 (26.3.0.7-beta).
// scripts/verify_spi_identity.py checks exactly that linkage in CI.
//
// It shares `neoforge-1.21.11/`'s FML-10 port and must NOT share `neoforge/`'s 21.x source:
// the rest of the surface broke at FML 10.0 (`IModFile.findResource` removed, SecureJar ->
// JarContents; `FMLEnvironment.dist` -> `getDist()`), and real 26.x ships loader 11/12, so
// 4.0.x-era source would NoSuchMethodError at runtime.
// `ModContainer.contextExtension` is absent here as on 1.21, so no ADR-0023 workaround.
mcdpBand {
    javaRelease.set(21)   // see fabric-26/build.gradle.kts for why not 25
    fmlModType.set("LIBRARY")
}

// Not setSrcDirs: mcdp.neoforge-band has already added neoforge-shared/, and replacing the
// list would drop it.
sourceSets {
    main {
        java.srcDir(rootProject.file("neoforge-1.21.11/src/main/java"))
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // The FML-10 pin (10.0.36), not a 26.x one: the 26.x fancymodloader jars are class-file
    // major 69 (Java 25) and javac on the root JDK 21 toolchain cannot read them at all
    // ("class file has wrong version 69.0"). The members the adapter references are the same
    // in 10.0.36 and loader 11.x/12.x (verify_spi_identity.py), so this links correctly at
    // runtime. Re-pin to `loader:12.0.0` once the root build has a JDK 25 toolchain —
    // cosmetic, not a fix.
    compileOnly(libs.neoforge.fml.loader.mc12111)
    compileOnly(libs.neoforge.bus)
}

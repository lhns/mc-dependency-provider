plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.neoforged.net/releases")
    maven("https://libraries.minecraft.net/")
}

// NeoForge for Mojang's calendar-versioning line (MC 26.1 / 26.2 / 26.3) — one band for the
// whole line (ADR-0032): `IModLanguageLoader`, `ModContainer`, `IModInfo` and
// `ModFileScanData` are byte-identical (same SHA-1) across fancymodloader 11.0.15
// (NeoForge 26.1.2.109), 11.0.16 (26.2.0.88) and 12.0.0 (26.3.0.7-beta).
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

sourceSets {
    main {
        java.setSrcDirs(listOf(
            rootProject.file("neoforge-1.21.11/src/main/java"),
            // setSrcDirs REPLACES, so the shared listener must be listed here too.
            rootProject.file("neoforge-shared/src/main/java"),
        ))
        resources.setSrcDirs(listOf("src/main/resources"))
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // The FML-10 pin (10.0.36), not a 26.x one: the 26.x fancymodloader jars are class-file
    // major 69 (Java 25) and javac on the root JDK 21 toolchain cannot read them at all
    // ("class file has wrong version 69.0"). The SPI classes are byte-for-byte identical
    // apart from that recompile, so this links correctly against loader 11.x/12.x at
    // runtime. Re-pin to `loader:12.0.0` once the root build has a JDK 25 toolchain —
    // cosmetic, not a fix.
    compileOnly(libs.neoforge.fml.loader.mc12111)
    compileOnly(libs.neoforge.bus)
}

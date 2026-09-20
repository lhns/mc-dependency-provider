plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.neoforged.net/releases")
    maven("https://libraries.minecraft.net/")
}

// NeoForge for Mojang's calendar-versioning line (MC 26.1 / 26.2 / 26.3) — one band, see
// ADR-0032. `IModLanguageLoader`, `ModContainer`, `IModInfo` and `ModFileScanData` are
// byte-identical (same SHA-1) across fancymodloader 11.0.15 (NeoForge 26.1.2.109),
// 11.0.16 (26.2.0.88) and 12.0.0 (26.3.0.7-beta), so one band covers the whole line.
//
// But those four are NOT the whole surface, and the rest of it broke at FML 10.0:
//   IModFile.findResource(String...)  -> removed; SecureJar replaced by JarContents
//   FMLEnvironment.dist (field)       -> FMLEnvironment.getDist()
// Verified with javap: loader 4.0.42 has findResource and the `dist` field; 10.0.36 and
// 12.0.0 have getContents() and getDist() and neither of the old members. So this band
// must NOT share `neoforge/`'s 21.x source -- it would compile against 4.0.x and then
// NoSuchMethodError on a real 26.x runtime, which ships loader 11/12.
//
// It shares `neoforge-1.21.11/`'s FML-10 port instead, and compiles against loader
// 10.0.36: class-file major 65, so javac on the root JDK 21 toolchain can read it,
// unlike 12.0.0 (major 69). Every member the port calls is present and identically
// signed in 12.0.0. That adapter is really the FML 10/11/12-generation adapter.
// `ModContainer.contextExtension` is absent here as on 1.21, so no ADR-0023 workaround.
mcdpBand {
    javaRelease.set(21)   // see fabric-26/build.gradle.kts for why not 25
    fmlModType.set("LIBRARY")
}

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("neoforge-1.21.11/src/main/java")))
        resources.setSrcDirs(listOf("src/main/resources"))
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // DELIBERATELY the 1.21-era fancymodloader pin (4.0.4x), not the 26.x one (11.x/12.x).
    // The 26.x fancymodloader jars are compiled at class-file major 69 (Java 25); javac on
    // the root JDK 21 toolchain cannot read them at all ("class file has wrong version
    // 69.0"). Since the SPI classes are byte-for-byte identical apart from that recompile
    // (verified above), compiling against 4.0.4x yields bytecode that links correctly
    // against fancymodloader 11.x/12.x at runtime. Re-pin to `loader:12.0.0` the moment the
    // root build gains a JDK 25 toolchain — that is a cosmetic change, not a fix.
    // loader 10.0.36, not the 4.0.x default: see the note above.
    compileOnly(libs.neoforge.fml.loader.mc12111)
    compileOnly(libs.neoforge.bus)
}

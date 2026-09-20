plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.fabricmc.net/")
}

// Fabric for MC 1.21.11 — the last release of the 1.21 line before Mojang switched to calendar
// versioning. Source and resources are shared with fabric/ (the 1.21.1 band): the two types this
// adapter implements, `net.fabricmc.loader.api.LanguageAdapter` and
// `net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint`, are byte-for-byte identical between
// fabric-loader 0.16.9 (the catalog pin) and 0.19.5 (what fabric-meta pairs with 1.21.11), as are
// every `FabricLoader`/`ModContainer` method the adapter calls. Verified with javap; see ADR-0030.
// So only the `fabric.mod.json` loader floor differs from the 1.21.1 band.
mcdpBand {
    javaRelease.set(21)
    // fabric-meta's loader/1.21.11 entry resolves to 0.19.5. The floor is deliberately the 0.19
    // series rather than 0.16 (the 1.21.1 band's floor): a 1.21.11 install running an older
    // loader is a configuration we have neither tested nor any reason to support.
    fabricLoaderVersion.set("0.19")
}

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("fabric/src/main/java")))
        resources.setSrcDirs(listOf(rootProject.file("fabric/src/main/resources")))
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.fabric.loader)
    compileOnly("org.slf4j:slf4j-api:2.0.9")
}

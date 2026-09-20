plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.fabricmc.net/")
}

// Fabric for MC 1.21.11 — the last release of the 1.21 line before calendar versioning. Source
// is shared with fabric/ (the 1.21.1 band): `LanguageAdapter`, `PreLaunchEntrypoint` and every
// `FabricLoader`/`ModContainer` member the adapter calls are byte-for-byte identical between
// fabric-loader 0.16.9 (the catalog pin) and 0.19.5 (what fabric-meta pairs with 1.21.11) —
// javap-verified, ADR-0030. So only the `fabric.mod.json` loader floor differs.
mcdpBand {
    javaRelease.set(21)
    // fabric-meta's loader/1.21.11 entry resolves to 0.19.5. Deliberately 0.19 rather than the
    // 1.21.1 band's 0.16: a 1.21.11 install on an older loader is untested and unsupported.
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

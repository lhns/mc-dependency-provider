plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.fabricmc.net/")
}

// Fabric for MC 1.19.x (1.19.2). Source is shared with fabric/ (the 1.21 band) — the Fabric
// LanguageAdapter + PreLaunchEntrypoint surface is stable across all fabric-loader 0.14+
// versions, so only the bytecode target and the `fabric.mod.json` floors differ per band.
mcdpBand {
    javaRelease.set(17)
    // fabric-loader 0.14.x is the line that shipped throughout MC 1.19's life (0.14.0 predates
    // 1.19; 0.15.0 only landed in Dec 2023, half a year after 1.19.4 was superseded). The floor
    // is the same as the 1.18 band's — a 0.15 floor would reject every contemporary 1.19 install.
    fabricLoaderVersion.set("0.14")
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

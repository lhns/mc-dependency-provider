plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.fabricmc.net/")
}

// Fabric for MC 1.17.x. Source is shared with fabric/ (the 1.21 band) — the Fabric
// LanguageAdapter + PreLaunchEntrypoint surface is stable across all fabric-loader 0.14+
// versions, so only the bytecode target differs per band.
mcdpBand {
    javaRelease.set(16)
}

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("fabric/src/main/java")))
        resources.setSrcDirs(listOf("src/main/resources"))
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.fabric.loader)
    compileOnly("org.slf4j:slf4j-api:2.0.9")
}

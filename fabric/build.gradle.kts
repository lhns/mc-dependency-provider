plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.fabricmc.net/")
}

// Fabric for MC 1.21.x. This is the band that owns the shared Fabric adapter source; the
// other fabric-* bands point their srcDirs here.
mcdpBand {
    javaRelease.set(21)
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.fabric.loader)
    // slf4j-api ships transitively with Fabric loader; declare compile visibility so we can
    // import LoggerFactory in our pre-launch hook.
    compileOnly("org.slf4j:slf4j-api:2.0.9")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testCompileOnly(libs.fabric.loader)
}

// This subproject (`:fabric-1.21`) is not published on its own. The band
// aggregator `:mcdp-1.21` (dir `multi/`) publishes the jar containing both the
// fabric and neoforge adapters. The shadowJar here remains produced for
// inspection/debugging and is consumed via `:mcdp-1.21`'s `bundle` configuration.

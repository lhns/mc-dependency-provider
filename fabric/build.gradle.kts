plugins {
    id("mcdp.fabric-band")
}

// Fabric for MC 1.21.x. This is the band that owns the shared Fabric adapter source *and*
// the shared `fabric.mod.json` template -- see the `mcdp.fabric-band` convention.
mcdpBand {
    javaRelease.set(21)
    fabricLoaderVersion.set("0.16.0")
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Not testCompileOnly: the convention's compileOnly surface never reaches the test
    // configurations, and the classes under test link against all four at runtime --
    // McdpPreLaunch's static initializer builds a LoaderCoordinator and an slf4j Logger, and
    // the tests proxy fabric-loader's FabricLoader / ModContainer / ModMetadata interfaces.
    // Only `fabric/` runs these: every other fabric-* band compiles the same source.
    testImplementation(project(":core"))
    testImplementation(project(":deps-lib"))
    testImplementation(libs.fabric.loader)
    testImplementation(libs.slf4j.api)
}

// This subproject (`:fabric-1.21`) is not published on its own. The band
// aggregator `:mcdp-1.21` (dir `multi/`) publishes the jar containing both the
// fabric and neoforge adapters. The shadowJar here remains produced for
// inspection/debugging and is consumed via `:mcdp-1.21`'s `bundle` configuration.

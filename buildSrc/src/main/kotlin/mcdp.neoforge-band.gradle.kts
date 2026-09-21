/**
 * The shared shape of a NeoForge adapter band: the NeoForged repository (plus Mojang's, for
 * `fancymodloader:loader`'s transitive `com.mojang:logging`) and the one source file every
 * NeoForge band compiles in common.
 *
 * `LoggingProgressListener` is identical on every NeoForge band -- it touches only deps-lib
 * and slf4j, and reaches FML's `StartupNotificationManager` reflectively by name, so it
 * compiles unchanged against loader 3.0.45 / 4.0.42 / 10.0.36. One canonical copy in
 * `neoforge-shared/`, compiled into each band's own jar so it stays package-private.
 *
 * Added with `srcDir`, not `setSrcDirs`: a band that redirects its main source tree at a
 * sibling band's (neoforge-26) must add rather than replace, or it drops this listener.
 */

plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.neoforged.net/releases")
    maven("https://libraries.minecraft.net/")
}

sourceSets {
    main {
        java.srcDir(rootProject.file("neoforge-shared/src/main/java"))
    }
}

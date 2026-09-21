import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * The shared shape of a Fabric adapter band: the FabricMC repository, `fabric/`'s source
 * tree, and the compile-only surface the adapter needs.
 *
 * `fabric/` (the `:fabric-1.21` band) owns the Fabric adapter source *and* the shared
 * `fabric.mod.json` template; every other fabric-* band points its srcDirs here. The Fabric
 * `LanguageAdapter` + `PreLaunchEntrypoint` surface is stable across all fabric-loader 0.14+
 * versions, so a band's own build file carries only what genuinely differs: its bytecode
 * target, its `fabric.mod.json` floors, and -- where a band needs it -- the loader artifact
 * it compiles against (`mcdpBand.fabricLoaderArtifact`).
 */

plugins {
    id("mcdp.band-adapter")
}

val mcdpBand = the<McdpBandExtension>()

// Precompiled script plugins get no type-safe `libs` accessor, so the catalog the outer
// build already declares is reached through its extension instead.
val libs = the<VersionCatalogsExtension>().named("libs")

repositories {
    maven("https://maven.fabricmc.net/")
}

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("fabric/src/main/java")))
        resources.setSrcDirs(listOf(rootProject.file("fabric/src/main/resources")))
    }
}

mcdpBand.fabricLoaderArtifact.convention(libs.findLibrary("fabric-loader").get())

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.findLibrary("jetbrains-annotations").get())
    compileOnly(mcdpBand.fabricLoaderArtifact)
    // slf4j-api ships transitively with Fabric loader; declare compile visibility so we can
    // import LoggerFactory in our pre-launch hook.
    compileOnly(libs.findLibrary("slf4j-api").get())
}

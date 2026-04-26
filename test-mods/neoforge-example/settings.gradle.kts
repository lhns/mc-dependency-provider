rootProject.name = "neoforge-example"

pluginManagement {
    // Composite-build the whole mc-lib-provider repo so local changes apply without
    // any publish step. The :gradle-plugin subproject supplies the mc-lib-provider plugin.
    includeBuild("../..")

    repositories {
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        gradlePluginPortal()
        mavenCentral()
    }
}

// ADR-0012: runtime library artifacts (de.lhns.mcdp:neoforge, :core)
// resolve via mavenLocal rather than composite substitution. The :neoforge subproject
// publishes a shadow jar containing core+deps-lib+tomlj; composite substitution
// would resolve to per-subproject source-set outputs instead of the shaded jar,
// which breaks NeoForge's language-provider discovery. Workflow:
//   ../../gradlew :neoforge:publishToMavenLocal
// before any runServer/runClient on this test mod.
dependencyResolutionManagement {
    repositories {
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        mavenLocal()
        mavenCentral()
    }
}

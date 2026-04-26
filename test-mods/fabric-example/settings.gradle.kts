rootProject.name = "fabric-example"

pluginManagement {
    // Composite-build the whole mc-lib-provider repo so the mc-lib-provider Gradle
    // plugin applies without a publish step.
    includeBuild("../..")

    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

// ADR-0012: runtime library artifacts (de.lhns.mcdp:fabric, :core)
// resolve via mavenLocal rather than composite substitution. The :fabric subproject
// publishes a shadow jar containing core+deps-lib+tomlj; composite substitution
// would resolve to per-subproject source-set outputs instead of the shaded jar,
// which Fabric's ClasspathModCandidateFinder rejects. Workflow:
//   ../../gradlew :fabric:publishToMavenLocal
// before any runServer/runClient on this test mod.

dependencyResolutionManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenLocal()
        mavenCentral()
    }
}

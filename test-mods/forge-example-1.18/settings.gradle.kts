rootProject.name = "forge-example-1.18"

// IMPORTANT: NO composite-include of ../.. here. The parent mc-scala build pulls
// Shadow 8.3.5 + other plugins that require Gradle 8, but ForgeGradle 5.1 (needed
// for MC 1.18.2) requires Gradle 7.x. Composite-include forces a single Gradle
// version — they're incompatible.
//
// Instead: publish mcdp-1.18 to mavenLocal from the parent build (on Gradle 8):
//     cd ../.. ; ./gradlew :mcdp-1.18:publishToMavenLocal
// Then build this test mod on Gradle 7.6:
//     cd test-mods/forge-example-1.18 ; ./gradlew build
//
// The mcdp-1.18 jar resolves from mavenLocal via `dependencyResolutionManagement`
// below; the de.lhns.mcdp gradle-plugin resolves from the snapshot repo + mavenLocal.

pluginManagement {
    repositories {
        // Snapshot repo of the published de.lhns.mcdp.gradle.plugin marker.
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenLocal()
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}

rootProject.name = "forge-example-1.17"

// No composite-include — ForgeGradle 5.1 forces Gradle 7 here, but the parent's
// Shadow 8.3.5 plugin requires Gradle 8. 1.17 is the one MC version Forge never
// regenerated an FG6 MDK for, so this is the last mod under that constraint —
// forge-example-1.18 moved to ForgeGradle 6 on the root wrapper (ADR-0023 errata).
// Run `:mcdp-1.17:publishToMavenLocal` from parent first, then build here.
pluginManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenLocal()
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "forge-example-1.17"

// No composite-include — ForgeGradle 5.1 forces Gradle 7 here, but the parent's
// Shadow 8.3.5 plugin requires Gradle 8. Same constraint as forge-example-1.18.
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}

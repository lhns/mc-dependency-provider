rootProject.name = "forge-example-1.18"

pluginManagement {
    includeBuild("../..")

    repositories {
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        gradlePluginPortal()
        mavenCentral()
    }
}

includeBuild("../..")

dependencyResolutionManagement {
    // ForgeGradle adds project repos at apply time; can't enforce strict mode here.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}

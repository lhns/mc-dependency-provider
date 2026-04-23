rootProject.name = "kotlin-example"

pluginManagement {
    // Composite-build the whole mc-lib-provider repo so we pick up local changes
    // without any publish step. The :gradle-plugin subproject supplies the plugin.
    includeBuild("../..")

    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

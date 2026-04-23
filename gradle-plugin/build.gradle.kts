plugins {
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("mclibprovider") {
            id = "io.github.mclibprovider"
            implementationClass = "io.github.mclibprovider.gradle.McLibProviderPlugin"
            displayName = "mc-lib-provider Gradle plugin"
            description = "Generates Maven dependency manifests and configures dev-mode runs for mc-lib-provider mods."
        }
    }
}

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
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

tasks.shadowJar {
    archiveClassifier.set("")
    // Shade deps-lib's Aether dependencies into a private namespace so the plugin is self-contained.
    relocate("org.apache.maven.resolver", "io.github.mclibprovider.shaded.aether.resolver")
    relocate("org.eclipse.aether", "io.github.mclibprovider.shaded.aether.eclipse")
}

tasks.named("jar", Jar::class) {
    enabled = false
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}

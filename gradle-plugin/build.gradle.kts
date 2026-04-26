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
        create("mcdp") {
            id = "de.lhns.mcdp"
            implementationClass = "de.lhns.mcdp.gradle.McdpProviderPlugin"
            displayName = "MC Dependency Provider Gradle plugin"
            description = "Generates Maven dependency manifests and configures dev-mode runs for mcdp mods."
        }
    }
}

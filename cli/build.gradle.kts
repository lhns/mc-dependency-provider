plugins {
    `java-library`
    application
}

application {
    mainClass.set("io.github.mclibprovider.cli.Prefetch")
}

dependencies {
    implementation(project(":deps-lib"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

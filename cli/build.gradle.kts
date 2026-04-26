plugins {
    `java-library`
    application
}

application {
    mainClass.set("de.lhns.mcdp.cli.Prefetch")
}

dependencies {
    implementation(project(":deps-lib"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

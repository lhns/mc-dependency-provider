plugins {
    `java-library`
}

repositories {
    maven("https://maven.fabricmc.net/")
}

dependencies {
    implementation(project(":core"))
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.fabric.loader)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testCompileOnly(libs.fabric.loader)
}

// Bundle core + deps-lib classes into the Fabric adapter jar. Fabric in dev mode
// groups classpath entries per mod-id; classes in a sibling Gradle subproject's
// jar aren't reachable to a mod whose fabric.mod.json only lives in this jar.
// For production this also matches the intended deliverable — one "mclibprovider"
// jar that end-users drop into their mods/ directory.
tasks.jar {
    dependsOn(":core:classes", ":deps-lib:classes")
    from(project(":core").sourceSets.named("main").map { it.output })
    from(project(":deps-lib").sourceSets.named("main").map { it.output })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

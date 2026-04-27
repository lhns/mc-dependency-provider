import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

repositories {
    maven("https://maven.fabricmc.net/")
}

val bundle by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.fabric.loader)
    // slf4j-api ships transitively with Fabric loader; declare compile visibility so we can
    // import LoggerFactory in our pre-launch hook.
    compileOnly("org.slf4j:slf4j-api:2.0.9")

    bundle(project(":core"))
    bundle(project(":deps-lib"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testCompileOnly(libs.fabric.loader)
}

// ADR-0012 resolution: produce a single shaded jar containing this subproject's
// classes plus :core and :deps-lib (and tomlj). That's both the production
// deliverable (one "mcdepprovider" jar) and the unblocker for Fabric's dev-mode
// ClasspathModCandidateFinder, which refuses to expose sibling composite-build
// jars to the mcdepprovider mod id.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("mcdp-fabric")
    configurations = listOf(bundle)
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}

configurations.apply {
    named("apiElements").configure {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.named("shadowJar"))
    }
    named("runtimeElements").configure {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.named("shadowJar"))
    }
}

// :fabric is no longer published. Only :dist publishes (the unified `mcdp` jar
// containing both fabric and neoforge adapters). The shadowJar here remains
// produced for inspection/debugging and is consumed via :dist's `bundle`
// configuration through apiElements/runtimeElements above.

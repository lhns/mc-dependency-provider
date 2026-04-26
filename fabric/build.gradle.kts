import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    `maven-publish`
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
    configurations = listOf(bundle)
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Same shading the neoforge subproject does. Fabric's Knot doesn't use JPMS
    // module layers, so unshaded tomlj would functionally work — but a Fabric
    // mod author who also ships tomlj would split-package collide with us, and
    // checker-qual annotations have no business in a runtime artifact. Match
    // neoforge for consistency.
    exclude("org/checkerframework/**")
    exclude("META-INF/versions/**/org/checkerframework/**")
    relocate("org.tomlj", "de.lhns.mcdp.shaded.tomlj")
    relocate("org.antlr", "de.lhns.mcdp.shaded.antlr")
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifact(tasks.named("shadowJar"))
        }
    }
}

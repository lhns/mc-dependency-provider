import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    id("com.gradleup.shadow")
}

val mcdpBand = extensions.create<McdpBandExtension>("mcdpBand")
mcdpBand.javaRelease.convention(21)
mcdpBand.archiveBaseName.convention(project.name)

// Overrides the root build's blanket `options.release` so each band compiles down to the
// Java version its Minecraft JVM actually ships. The toolchain JDK stays 21 (root).
tasks.withType<JavaCompile>().configureEach {
    options.release.set(mcdpBand.javaRelease)
}

// Everything shaded into this module's jar is declared on `bundle`; it is deliberately not
// a consumable configuration, so nothing leaks into the published dependency graph.
val bundle by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// ADR-0012 resolution: produce a single shaded, classifier-less jar rather than a thin jar
// plus loose dependencies. For the adapters that is both the production deliverable (one
// "mcdepprovider" jar) and the unblocker for Fabric's dev-mode ClasspathModCandidateFinder,
// which refuses to expose sibling composite-build jars to the mcdepprovider mod id.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set(mcdpBand.archiveBaseName)
    configurations = listOf(bundle)
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mcdpBand.fmlModType.orNull?.let { modType ->
        // Automatic-Module-Name is a JPMS hint for FML's classloader pipeline; Fabric
        // ignores both attributes.
        manifest {
            attributes(
                "FMLModType" to modType,
                "Automatic-Module-Name" to "mcdepprovider"
            )
        }
    }
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}

// The raw `jar` is disabled above, so the outgoing variants would otherwise advertise a file
// that is never built. Rewiring them to the shadowJar is also what makes a consumer's
// `bundle(project(":..."))` pick up the *shaded* jar, and what makes the published software
// component carry it.
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

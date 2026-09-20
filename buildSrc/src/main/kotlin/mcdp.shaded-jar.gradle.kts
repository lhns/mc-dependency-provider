import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    id("com.gradleup.shadow")
}

val mcdpBand = extensions.create<McdpBandExtension>("mcdpBand")

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
    // Band aggregators are named for the artifactId they publish; `mcdp.band-adapter`
    // overrides this with the `mcdp-` prefix its modules' names lack.
    archiveBaseName.set(project.name)
    configurations = listOf(bundle)
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Automatic-Module-Name is a JPMS hint for FML's classloader pipeline; Fabric ignores it.
    manifest {
        attributes("Automatic-Module-Name" to "mcdepprovider")
    }
    // FMLModType is what actually makes FML route the jar, so it is only set where FML
    // should pick it up — see McdpBandExtension. Unset must stay "no attribute": an empty
    // or bogus value is worse than none.
    //
    // Resolved at execution time rather than with an eager `.orNull` here: if anything ever
    // realizes this task before the band's `mcdpBand { }` block runs, an eager read silently
    // drops the attribute, and the jar then publishes fine while FML never routes it.
    val fmlModType = mcdpBand.fmlModType
    inputs.property("fmlModType", fmlModType).optional(true)
    doFirst {
        fmlModType.orNull?.let { manifest.attributes("FMLModType" to it) }
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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.shadow)
}

// `:mcdp` is the only published artifact (`de.lhns.mcdp:mcdp`). It bundles
// `:fabric` and `:neoforge`'s shadowJars (which already contain `:core` +
// `:deps-lib`) into one multi-loader jar. Both metadata files coexist:
// `fabric.mod.json` at the jar root (Fabric reads it); the NeoForge side uses
// `META-INF/services/IModLanguageLoader` + `FMLModType=LIBRARY` for discovery
// (no `neoforge.mods.toml` since this is a LIBRARY-type provider, not a mod).
// Each loader ignores the other's metadata, and the unused entry classes
// (referencing platform-specific APIs the other loader doesn't ship) never
// link because no live code path references them on the wrong platform.
//
// The Gradle project name (`:mcdp`) matches the published artifactId so
// composite-build auto-substitution from consumer test-mods works without
// explicit dependencySubstitution rules — `de.lhns.mcdp:mcdp` requested by
// a consumer auto-resolves to this project.

val bundle by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // `:fabric` and `:neoforge` rewire their `apiElements`/`runtimeElements`
    // outgoing artifacts to point at their respective shadowJars (see ADR-0012),
    // so this `bundle` configuration receives those shaded jars and not the
    // raw source-set `jar` outputs. Each shaded jar already contains `:core`
    // and `:deps-lib`; shadow's EXCLUDE strategy below drops the duplicates.
    bundle(project(":fabric"))
    bundle(project(":neoforge"))
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("mcdp")
    configurations = listOf(bundle)
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // FMLModType=LIBRARY routes the jar to NeoForge's PLUGIN module layer where
    // FML ServiceLoader-scans IModLanguageLoader. Fabric ignores the manifest
    // attribute. Automatic-Module-Name is a JPMS hint for NeoForge's classloader
    // pipeline; harmless to Fabric.
    manifest {
        attributes(
            "FMLModType" to "LIBRARY",
            "Automatic-Module-Name" to "mcdepprovider"
        )
    }
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
            artifactId = "mcdp"
            artifact(tasks.named("shadowJar"))
        }
    }
}

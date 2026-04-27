import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-library`
    alias(libs.plugins.shadow)
    alias(libs.plugins.vanniktech.maven.publish)
}

// This subproject (Gradle path `:mcdp`, on-disk dir `multi/`) is the only
// runtime artifact published as `de.lhns.mcdp:mcdp`. It bundles `:fabric` and
// `:neoforge`'s shadowJars (which already contain `:core` + `:deps-lib`) into
// one multi-loader jar. Both metadata files coexist: `fabric.mod.json` at the
// jar root (Fabric reads it); the NeoForge side uses
// `META-INF/services/IModLanguageLoader` + `FMLModType=LIBRARY` for discovery
// (no `neoforge.mods.toml` since this is a LIBRARY-type provider, not a mod).
// Each loader ignores the other's metadata, and the unused entry classes
// (referencing platform-specific APIs the other loader doesn't ship) never
// link because no live code path references them on the wrong platform.
//
// The Gradle project *name* (`:mcdp`) matches the published artifactId so
// composite-build auto-substitution from consumer test-mods works without
// explicit dependencySubstitution rules. The on-disk *directory* is `multi/`,
// set via `project(":mcdp").projectDir = file("multi")` in
// settings.gradle.kts, to describe what the module does (multi-loader bundle).

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

// Maven Central publishing via vanniktech. Picks up the project's `java`/`java-library` software
// component (whose apiElements/runtimeElements were rewired above to publish the shadowJar
// instead of the empty raw `jar`). Credentials read from env vars in CI:
// ORG_GRADLE_PROJECT_mavenCentralUsername / ORG_GRADLE_PROJECT_mavenCentralPassword and
// ORG_GRADLE_PROJECT_signingInMemoryKey / ...keyId / ...keyPassword.
mavenPublishing {
    // vanniktech 0.32.0 — Central Portal snapshot publishing supported (added in 0.31).
    // automaticRelease=false: release bundles stage at https://central.sonatype.com/publishing
    // for manual review. Snapshots upload directly to the Central Portal snapshots repo
    // (requires snapshot-publishing enabled on the namespace via the Central Portal UI).
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    signAllPublications()
    coordinates("de.lhns.mcdp", "mcdp", project.version.toString())
    pom {
        name.set("mcdp")
        description.set("Multi-loader (Fabric + NeoForge) JVM-language mod provider with per-mod Maven dependency isolation.")
        url.set("https://github.com/lhns/mc-dependency-provider")
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("lhns")
                name.set("Pierre Kisters")
                email.set("pierrekisters@gmail.com")
            }
        }
        scm {
            url.set("https://github.com/lhns/mc-dependency-provider")
            connection.set("scm:git:https://github.com/lhns/mc-dependency-provider.git")
            developerConnection.set("scm:git:ssh://git@github.com/lhns/mc-dependency-provider.git")
        }
    }
}

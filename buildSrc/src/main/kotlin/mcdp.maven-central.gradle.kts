import com.vanniktech.maven.publish.SonatypeHost

/**
 * Maven Central publishing, identical for every artifact this repo publishes: the band
 * aggregators and `:gradle-plugin`. Only the POM `<name>`/`<description>` differ, and those
 * stay with the module that publishes them.
 */

plugins {
    id("com.vanniktech.maven.publish")
}

// It picks up the `java-library` software component, whose apiElements/runtimeElements are
// rewired to the shadowJar instead of the disabled raw `jar`. Credentials come from env vars
// in CI: ORG_GRADLE_PROJECT_mavenCentralUsername / ...Password and
// ORG_GRADLE_PROJECT_signingInMemoryKey / ...keyId / ...keyPassword.
mavenPublishing {
    // vanniktech 0.32.0 -- Central Portal snapshot publishing supported (added in 0.31).
    // automaticRelease=true (ADR-0026, which supersedes ADR-0020's staging gate): release
    // bundles upload AND auto-invoke the Portal's release-now API so the artifact lands on
    // Maven Central without a manual click. The publish workflow already gates release-event
    // runs on Tier 1 + Tier 2 success -- that's the trust boundary.
    // Snapshots ignore this flag (they always go straight to the Central Portal snapshots repo,
    // requires snapshot-publishing enabled on the namespace via the Portal UI).
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    // The project name *is* the artifactId: composite-build auto-substitution maps
    // `de.lhns.mcdp:<artifactId>` to the project of the same name (see settings.gradle.kts).
    coordinates("de.lhns.mcdp", project.name, project.version.toString())
    pom {
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

import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("mcdp.shaded-jar")
    id("com.vanniktech.maven.publish")
}

val mcdpBand = the<McdpBandExtension>()

// Maven Central publishing via vanniktech. It picks up the `java-library` software component,
// whose apiElements/runtimeElements mcdp.shaded-jar rewired to the shadowJar instead of the
// disabled raw `jar`. Credentials come from env vars in CI:
// ORG_GRADLE_PROJECT_mavenCentralUsername / ...Password and
// ORG_GRADLE_PROJECT_signingInMemoryKey / ...keyId / ...keyPassword.
mavenPublishing {
    // vanniktech 0.32.0 — Central Portal snapshot publishing supported (added in 0.31).
    // automaticRelease=true (ADR-0026, which supersedes ADR-0020's staging gate): release
    // bundles upload AND auto-invoke the Portal's release-now API so the artifact lands on
    // Maven Central without a manual click. The publish workflow already gates release-event
    // runs on Tier 1 + Tier 2 success — that's the trust boundary.
    // Snapshots ignore this flag (they always go straight to the Central Portal snapshots repo,
    // requires snapshot-publishing enabled on the namespace via the Portal UI).
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    // The project name *is* the artifactId: composite-build auto-substitution maps
    // `de.lhns.mcdp:<artifactId>` to the project of the same name (see settings.gradle.kts).
    coordinates("de.lhns.mcdp", project.name, project.version.toString())
    pom {
        // The project name *is* the artifactId (see `coordinates` above), and that is
        // exactly what every band wants as the POM `<name>`.
        name.set(project.name)
        description.set(mcdpBand.pomDescription)
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

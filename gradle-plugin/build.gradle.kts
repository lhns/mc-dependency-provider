import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.vanniktech.maven.publish)
}

// vanniktech-maven-publish auto-configures sources + javadoc jars; do not call
// java { withSourcesJar(); withJavadocJar() } here or duplicate artifacts crash publishing.

dependencies {
    implementation(project(":deps-lib"))
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)
    compileOnly(libs.jetbrains.annotations)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.asm.util)
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("mcdp") {
            id = "de.lhns.mcdp"
            implementationClass = "de.lhns.mcdp.gradle.McdpProviderPlugin"
            displayName = "MC Dependency Provider Gradle plugin"
            description = "Generates Maven dependency manifests and configures dev-mode runs for mcdp mods."
        }
    }
}

// Maven Central publishing. `java-gradle-plugin` already creates a `pluginMaven` publication
// (and per-plugin marker publications); vanniktech picks them up automatically. We don't apply
// `com.gradle.plugin-publish` here — Plugin Portal publication is out of scope; consumers can
// resolve the plugin from Maven Central via `pluginManagement { repositories { mavenCentral() } }`.
mavenPublishing {
    // vanniktech 0.32.0 — same shape as in :mcdp; see comment there.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    signAllPublications()
    coordinates("de.lhns.mcdp", "gradle-plugin", project.version.toString())
    pom {
        name.set("mcdp Gradle plugin")
        description.set("Generates Maven dependency manifests and configures dev-mode runs for mcdp mods.")
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

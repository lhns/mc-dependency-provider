import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.plugin.devel.tasks.PluginUnderTestMetadata

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.shadow)
    alias(libs.plugins.vanniktech.maven.publish)
}

// vanniktech-maven-publish auto-configures sources + javadoc jars; do not call
// java { withSourcesJar(); withJavadocJar() } here or duplicate artifacts crash publishing.

// :deps-lib is shaded into the published gradle-plugin jar (ADR-0012 pattern,
// matches :fabric / :neoforge / :mcdp). It's a sibling project that we choose
// not to publish (ADR-0016), so its classes need to ship inside this artifact
// or the published POM points at a coordinate consumers can't resolve.
val bundle by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly(project(":deps-lib"))
    bundle(project(":deps-lib"))
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)
    compileOnly(libs.jetbrains.annotations)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.asm.util)
    testImplementation(gradleTestKit())
    // Direct (non-TestKit) tests in this sourceset reference deps-lib classes;
    // bundle alone doesn't put them on the test classpath.
    testImplementation(project(":deps-lib"))
}

// TestKit reads pluginUnderTestMetadata to build the plugin classpath inside
// the test JVM. Default = main runtimeClasspath, which no longer carries
// :deps-lib (we moved it to compileOnly + bundle). Append it explicitly so
// integration tests see the shaded classes exactly like a published consumer.
tasks.named<PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(project(":deps-lib").sourceSets.named("main").get().runtimeClasspath)
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("gradle-plugin")
    configurations = listOf(bundle)
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}

// Rewire the published software-component variants so the pluginMaven
// publication (auto-created by java-gradle-plugin and picked up by vanniktech)
// carries the shadow jar instead of the raw, deps-lib-less main jar. Same
// six-line block as fabric/build.gradle.kts.
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
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
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

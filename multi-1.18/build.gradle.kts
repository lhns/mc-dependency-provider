import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-library`
    alias(libs.plugins.shadow)
    alias(libs.plugins.vanniktech.maven.publish)
}

// MC 1.18 band aggregator. Bundles :fabric-1.18 and :forge-1.18 shadowJars into one
// `de.lhns.mcdp:mcdp-1.18` runtime jar. NeoForge does not exist on this band — the
// NeoForge fork started at 1.20.5+, so 1.18 is Fabric + Forge only.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

val bundle by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    bundle(project(":fabric-1.18"))
    bundle(project(":forge-1.18"))
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("mcdp-1.18")
    configurations = listOf(bundle)
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Automatic-Module-Name" to "mcdepprovider",
            "FMLModType" to "LANGPROVIDER"
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

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    coordinates("de.lhns.mcdp", "mcdp-1.18", project.version.toString())
    pom {
        name.set("mcdp-1.18")
        description.set("Multi-loader (Fabric + Forge) JVM-language mod provider for MC 1.18.")
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

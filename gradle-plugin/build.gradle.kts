import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.plugin.devel.tasks.PluginUnderTestMetadata
import org.gradle.process.CommandLineArgumentProvider
import java.util.zip.ZipFile

plugins {
    `java-gradle-plugin`
    // Versions come from buildSrc's classpath; see the note in the root build.gradle.kts.
    id("com.gradleup.shadow")
    id("com.vanniktech.maven.publish")
}

// vanniktech-maven-publish auto-configures sources + javadoc jars; do not call
// java { withSourcesJar(); withJavadocJar() } here or duplicate artifacts crash publishing.

// :deps-lib is shaded into the published gradle-plugin jar (ADR-0012 pattern,
// matches the band modules — :fabric-1.21, :neoforge-1.21, :mcdp-1.21, …). It's a
// sibling project that we choose not to publish (ADR-0016), so its classes need to
// ship inside this artifact or the published POM points at a coordinate consumers
// can't resolve.
val bundle by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly(project(":deps-lib"))
    bundle(project(":deps-lib"))
    // ASM is shaded AND relocated (see shadowJar below), so it is compileOnly + bundle rather
    // than implementation. As a plain POM dependency it lost: Gradle exports its own
    // `org.objectweb.asm` to the plugin classloader, so the codegen ran against whatever ASM the
    // daemon shipped, not the pinned one. That surfaced as `Unsupported class file major version
    // 69` on the MC 26.x cell — a JDK 25 toolchain — even after the pin here was raised.
    compileOnly(libs.asm)
    compileOnly(libs.asm.tree)
    compileOnly(libs.asm.commons)
    bundle(libs.asm)
    bundle(libs.asm.tree)
    bundle(libs.asm.commons)
    compileOnly(libs.jetbrains.annotations)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.asm)
    testImplementation(libs.asm.tree)
    testImplementation(libs.asm.commons)
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
    // ASM moved to compileOnly + bundle, so runtimeClasspath no longer carries it either.
    pluginClasspath.from(bundle)
}

// ShadowJarCodegenTest runs a real codegen build against the shadow jar, which is the only
// place the RELOCATED ASM is ever executed: pluginUnderTestMetadata above feeds TestKit the
// un-relocated `bundle`, so every other integration test proves nothing about the relocation.
// The jar path is handed over as a system property; an argument provider keeps it lazy (and
// configuration-cache clean) while `inputs.file` carries the task dependency.
tasks.named<Test>("test") {
    val shadowJarFile = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    dependsOn(tasks.named("shadowJar"))
    inputs.file(shadowJarFile)
            .withPropertyName("shadowJarUnderTest")
            .withNormalizer(ClasspathNormalizer::class.java)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dmcdp.shadow.jar=${shadowJarFile.get().asFile.absolutePath}")
    })
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
    // Relocation, not just bundling: Gradle's own `org.objectweb.asm` is visible to the plugin
    // classloader and wins over a plugin's, so the codegen silently ran against the daemon's ASM.
    // Under a relocated package nothing outside this jar can shadow it, and the bytecode this
    // plugin reads is bounded by the pin in libs.versions.toml rather than by the Gradle version.
    relocate("org.objectweb.asm", "de.lhns.mcdp.gradle.shaded.asm")
}

// The relocation above is load-bearing and invisible in source, so assert it on the artifact.
val verifyAsmRelocated by tasks.registering {
    dependsOn(tasks.named("shadowJar"))
    val jar = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    inputs.file(jar)
    doLast {
        ZipFile(jar.get().asFile).use { zip ->
            val leaked = zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("org/objectweb/asm/") }
                    .toList()
            require(leaked.isEmpty()) {
                "ASM was not relocated; ${leaked.size} entries still under org/objectweb/asm/, " +
                        "e.g. ${leaked.first()}. Gradle's own ASM would shadow these at runtime."
            }
            require(zip.getEntry("de/lhns/mcdp/gradle/shaded/asm/ClassReader.class") != null) {
                "relocated ASM missing from the plugin jar entirely"
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyAsmRelocated) }

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}

// Rewire the published software-component variants so the pluginMaven
// publication (auto-created by java-gradle-plugin and picked up by vanniktech)
// carries the shadow jar instead of the raw, deps-lib-less main jar. Same block
// as buildSrc/src/main/kotlin/mcdp.shaded-jar.gradle.kts does for the band modules;
// this project can't use that convention plugin (it is a java-gradle-plugin, not a
// band), so the rewiring is repeated here.
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
    // vanniktech 0.32.0 — same shape as the band aggregators; see the comment in
    // buildSrc/src/main/kotlin/mcdp.band-aggregator.gradle.kts for what
    // automaticRelease=true implies (ADR-0026).
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

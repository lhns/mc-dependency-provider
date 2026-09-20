plugins {
    `java-library`
}

// The shadow and vanniktech-maven-publish plugins are pulled in by buildSrc (see
// buildSrc/build.gradle.kts) and are therefore already on every build script's classpath in
// a single classloader. That matters for vanniktech: its SonatypeRepositoryBuildService is
// otherwise loaded twice and Gradle rejects the cross-classloader build-service handoff.
// Consequence: scripts must request them *without* a version — `id("com.gradleup.shadow")`,
// not `alias(libs.plugins.shadow)` — or Gradle refuses the already-on-classpath request.

allprojects {
    group = "de.lhns.mcdp"
    // Default to a snapshot version for local + push:main builds; the publish workflow
    // overrides via -Pversion=<tag> when triggered by a `release: published` event so
    // vanniktech routes to the release endpoint. automaticRelease=true (set in
    // buildSrc/src/main/kotlin/mcdp.band-aggregator.gradle.kts, not here — see ADR-0026)
    // then publishes without a Portal click.
    version = providers.gradleProperty("version").orElse("0.1.0-SNAPSHOT").get()
}

subprojects {
    apply(plugin = "java-library")

    // Toolchain JDK is 21 across the board (build runs on Java 21), but the bytecode
    // *target* differs per subproject so older Minecraft bands can consume the same
    // shared library code on their lower JVMs. core/ and deps-lib/ target Java 16 — the
    // lowest LTS-ish Minecraft band (1.17, which ships with Java 16) we want to support.
    // Java-17-only language features (sealed types, pattern-matching enhancements) aren't
    // used in core/ or deps-lib/; records, switch expressions, StackWalker, and var are
    // all stable at Java 16 or earlier. Adapter subprojects (fabric/, neoforge/, multi/)
    // keep Java 21 because the currently-shipped band is MC 1.21.x. Multi-band fan-out
    // (additional fabric-1.20/, neoforge-1.20.6/ etc. subprojects) picks its own target
    // per band.
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    val javaTarget = if (name in setOf("core", "deps-lib")) 16 else 21

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(javaTarget)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    repositories {
        mavenCentral()
    }
}

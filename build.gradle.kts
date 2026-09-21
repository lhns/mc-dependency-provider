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

    // :gradle-plugin is a *build-time* artifact: its bytecode is loaded by the Gradle
    // daemon JVM, never by a Minecraft server JVM. So the MC-1.17 Java-16 runtime floor
    // (the reason core/ and deps-lib/ target 16) does not apply to it. What does apply is
    // the daemon JVM of the oldest build we want to support: test-mods/forge-example-1.17
    // is on ForgeGradle 5.1, which pins Gradle 7.x, and Gradle 7.6 runs on Java 8–19.
    // Targeting 21 made the plugin unloadable on any Gradle 7 daemon — which is what
    // excluded the Forge 1.17/1.18 bands from CI. (1.18 no longer needs this: the official
    // forge-1.18.2-40.3.12 MDK is ForgeGradle 6 / Gradle 8.8, so forge-example-1.18 runs on
    // the root 8.11.1 wrapper. FG 5.1 was never "the only FG line for MC ≤ 1.18" — see
    // ADR-0023's errata. 1.17 is the one version with no FG6 MDK, so it still sets the
    // floor here.) Target 17: it is the Gradle-7.6-compatible level that MC 1.18.2 already
    // requires, and 17 (not 16) because RunTaskClasspathPatch uses
    // java.util.HexFormat, a Java 17 API. Nothing in gradle-plugin/src/main uses a Gradle
    // API newer than 7.6 (verified by import inventory + API-surface grep), so 17 is
    // sufficient — no source change needed.
    val javaTarget = when {
        name in setOf("core", "deps-lib") -> 16
        name == "gradle-plugin" -> 17
        else -> 21
    }

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

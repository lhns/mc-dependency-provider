plugins {
    `java-library`
    // Loaded at root so the plugin's classes share one classloader across :mcdp and
    // :gradle-plugin (vanniktech's SonatypeRepositoryBuildService is otherwise loaded twice
    // and Gradle rejects the cross-classloader build-service handoff).
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

allprojects {
    group = "de.lhns.mcdp"
    // Default to a snapshot version for local + push:main builds; the publish workflow
    // overrides via -Pversion=<tag> when triggered by a `release: published` event so
    // vanniktech routes to the release endpoint (and automaticRelease=true below auto-
    // publishes without a Portal click).
    version = providers.gradleProperty("version").orElse("0.1.0-SNAPSHOT").get()
}

subprojects {
    apply(plugin = "java-library")

    // Toolchain JDK is 21 across the board (build runs on Java 21), but the bytecode
    // *target* differs per subproject so older Minecraft bands can consume the same
    // shared library code on their lower JVMs. core/ and deps-lib/ target Java 17 — the
    // lowest LTS Minecraft band (1.18 — 1.20.4) we want to support. Adapter subprojects
    // (fabric/, neoforge/, multi/) target Java 21 because the only band currently shipped
    // is MC 1.21.x (Java 21). Multi-band fan-out (additional fabric-1.20/, neoforge-1.20.6/
    // etc. subprojects) will pick their own targetCompatibility per band.
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    val javaTarget = if (name in setOf("core", "deps-lib")) 17 else 21

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

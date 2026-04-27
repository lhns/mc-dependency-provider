plugins {
    `java-library`
    // Loaded at root so the plugin's classes share one classloader across :mcdp and
    // :gradle-plugin (vanniktech's SonatypeRepositoryBuildService is otherwise loaded twice
    // and Gradle rejects the cross-classloader build-service handoff).
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

allprojects {
    group = "de.lhns.mcdp"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    repositories {
        mavenCentral()
    }
}

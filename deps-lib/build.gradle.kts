plugins {
    `java-library`
}

java {
    withSourcesJar()
    withJavadocJar()
}

sourceSets {
    // The Aether-backed ManifestProducer lives here and is only compiled into
    // the `full` jar. Runtime consumers never pull the resolver in.
    val main by getting
    val full by creating {
        compileClasspath += main.output
        runtimeClasspath += main.output
    }
    val fullTest by creating {
        compileClasspath += main.output + full.output
        runtimeClasspath += main.output + full.output
    }
}

configurations {
    named("fullImplementation") { extendsFrom(implementation.get()) }
    named("fullRuntimeOnly") { extendsFrom(runtimeOnly.get()) }
    named("fullTestImplementation") {
        extendsFrom(testImplementation.get())
        extendsFrom(named("fullImplementation").get())
    }
    named("fullTestRuntimeOnly") { extendsFrom(testRuntimeOnly.get()) }
}

dependencies {
    implementation(libs.tomlj)
    compileOnly(libs.jetbrains.annotations)

    "fullImplementation"(libs.maven.resolver.supplier)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val fullJar by tasks.registering(Jar::class) {
    archiveClassifier.set("full")
    from(sourceSets.main.get().output)
    from(sourceSets["full"].output)
}

tasks.named("assemble") {
    dependsOn(fullJar)
}

val fullTest by tasks.registering(Test::class) {
    description = "Runs tests against the Aether-backed producer (slow — hits Maven Central)."
    group = "verification"
    testClassesDirs = sourceSets["fullTest"].output.classesDirs
    classpath = sourceSets["fullTest"].runtimeClasspath
    useJUnitPlatform()
    // Opt-in only; `./gradlew check` doesn't depend on this.
}

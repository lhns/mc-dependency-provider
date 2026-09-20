rootProject.name = "fabric-example-26.3"

// IMPORTANT: NO composite-include of ../.. here — same reason as
// `forge-example-1.17` / `-1.18`, only in the other direction.
//
// MC 26.x requires a **Java 25** runtime (the version manifest for 26.1, 26.2 and 26.3 all
// say `javaVersion.majorVersion = 25`). Loom refuses to set up a MC version whose required
// Java is above the JVM the *Gradle daemon* is running on — that is the
// "26.1.2 requires Java 25 but Gradle is using 21" error already recorded in
// `.github/workflows/mc-smoke.yml`. A JDK 25 daemon in turn needs Gradle 9.x; the root
// mc-scala build is on Gradle 8.11.1 / JDK 21 and stays there. Composite-include forces a
// single Gradle version and a single daemon JVM, so it is impossible here.
//
// Instead, exactly like the ForgeGradle bands:
//     cd ../.. ; ./gradlew :mcdp-26:publishToMavenLocal :gradle-plugin:publishToMavenLocal
//     cd test-mods/fabric-example-26.3 ; ./gradlew build     # this mod's own Gradle 9.6 wrapper
//
// `de.lhns.mcdp:mcdp-26:0.1.0-SNAPSHOT` and the `de.lhns.mcdp` plugin marker then resolve
// from mavenLocal (project `repositories` below and in build.gradle.kts).

pluginManagement {
    repositories {
        // Snapshot repo of the published de.lhns.mcdp.gradle.plugin marker.
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenLocal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

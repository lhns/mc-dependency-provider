rootProject.name = "neoforge-example-26.2"

// NO composite-include of ../.. — same reason as `fabric-example-26.3`: MC 26.x requires a
// Java 25 runtime, and the root mc-scala build is Gradle 8.11.1 on JDK 21.
//
// Worth knowing (and worth trying before anyone invests in this standalone shape):
// ModDevGradle's constraint may be weaker than Loom's. Loom hard-checks the *Gradle daemon*
// JVM against the MC version's required Java ("26.1.2 requires Java 25 but Gradle is using
// 21"). MDG resolves the run/compile JVM through Gradle's Java toolchain mechanism, so a
// `toolchain { languageVersion = 25 }` may be enough to let this mod stay composite-included
// on the root Gradle 8.11.1 / JDK 21 daemon. That has NOT been verified (no build was run).
// If it holds, delete this directory's wrapper properties, restore `includeBuild("../..")`
// and the cell can join CI without a second Gradle distribution.
//
// Until then, the ForgeGradle-band recipe:
//     cd ../.. ; ./gradlew :mcdp-26:publishToMavenLocal :gradle-plugin:publishToMavenLocal
//     cd test-mods/neoforge-example-26.2 ; ./gradlew build

pluginManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenLocal()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}

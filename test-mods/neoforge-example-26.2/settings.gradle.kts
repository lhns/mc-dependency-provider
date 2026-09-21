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
// If it holds, delete this directory's wrapper (properties, jar and launcher scripts)
// and restore `includeBuild("../..")` — the cell would then need no second distribution.
//
// Until then, the ForgeGradle-band recipe:
//     cd ../.. ; ./gradlew :mcdp-26:publishToMavenLocal :gradle-plugin:publishToMavenLocal
//     cd test-mods/neoforge-example-26.2 ; ./gradlew build   # this mod's own Gradle 9.7.1 wrapper

pluginManagement {
    repositories {
        // mavenLocal FIRST. The published Sonatype snapshot below is a fallback only: listed
        // ahead of mavenLocal it wins plugin resolution outright, and this mod then builds
        // against whatever snapshot was last published rather than the tree it sits in. That
        // silently masked an ASM fix for two CI runs.
        mavenLocal()
        // Fallback for a checkout that has not run `publishToMavenLocal` from the repo root.
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        gradlePluginPortal()
        mavenCentral()
    }
}

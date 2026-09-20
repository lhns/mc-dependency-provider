# fabric-example-26.3

Fabric test mod for **MC 26.3** (released 2026-09-15), targeting the single
`de.lhns.mcdp:mcdp-26` band (ADR-0032 — it replaces the old `mcdp-26.1` scaffold).

## How to build it

This mod is **not** composite-included into the root build and does **not** use the
repo-root `gradlew`. MC 26.x requires a Java 25 runtime, Loom refuses a MC version
whose required Java exceeds the Gradle daemon's JVM, and a JDK 25 daemon requires
Gradle 9.x — the root build is Gradle 8.11.1 / JDK 21 and stays there.

```sh
cd ../.. && ./gradlew :mcdp-26:publishToMavenLocal :gradle-plugin:publishToMavenLocal
cd test-mods/fabric-example-26.3 && ./gradlew build        # this dir's own Gradle 9.7.1, JDK 25
```

Same shape as `forge-example-1.17`: own wrapper, no
`includeBuild("../..")`, `mavenLocal()` first in project `repositories`, and
`cacheChangingModulesFor(0, "seconds")` so the just-published SNAPSHOT is the one
that resolves.

## The wrapper

The **full** wrapper is committed: `gradle/wrapper/gradle-wrapper.properties`,
`gradle/wrapper/gradle-wrapper.jar`, `gradlew` and `gradlew.bat`. The binaries were
copied verbatim from the repo-root wrapper — they are version-agnostic bootstrappers
that read `distributionUrl` from the properties file beside them, so the root's 8.11.1
binaries download and launch 9.7.1 here exactly as `forge-example-1.17`'s identical
copies launch 7.6. (An earlier note here claimed they "cannot be produced without a
local Gradle 9.6". That was wrong.)

**Why 9.7.1 and not 9.6.x:** Loom 1.18.x publishes `runtimeElements` with
`org.gradle.plugin.api-version = 9.7.0`, so a 9.6.1 consumer is rejected at variant
selection and never loads the plugin at all. 9.7.0 is the floor; 9.7.1 is current.

## CI status

This cell is now a row in `runserver-smoke-bands` (`daemon_jdk: "25"`, `25` added to
the job's `setup-java` list before `21`). **It has not yet passed** — no nightly has
run it. Two things are wired but unproven: whether Loom 1.18.2 can actually provision
MC 26.3, and whether the mcdp Gradle plugin loads on a Gradle 9.x daemon at all.

## Mappings

There is no yarn for the calendar line — `net.fabricmc:yarn` stops at 1.21.11 and
`meta.fabricmc.net/v2/versions/yarn/26.3` is empty. This mod uses
`loom.officialMojangMappings()`. (The old `fabric-example-26.1` scaffold pinned
`net.fabricmc:yarn:26.1.2+build.1`, which never existed and would not have resolved.)

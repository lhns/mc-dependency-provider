# fabric-example-26.3

Fabric test mod for **MC 26.3** (released 2026-09-15), targeting the single
`de.lhns.mcdp:mcdp-26` band (ADR-0032).

Green in the nightly `runserver-smoke-bands` matrix (`fabric-26.3`, `daemon_jdk: "25"`) on
both OSes, and wired into the Tier-3 `runClient` nightly.

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
that resolves. `mavenLocal()` must also come *first* under `pluginManagement`, or the
Sonatype snapshot repo wins and the build silently exercises the last published jar
instead of the tree it sits in.

## The wrapper

The **full** wrapper is committed: `gradle/wrapper/gradle-wrapper.properties`,
`gradle/wrapper/gradle-wrapper.jar`, `gradlew` and `gradlew.bat`. The binaries were
copied verbatim from the repo-root wrapper — they are version-agnostic bootstrappers
that read `distributionUrl` from the properties file beside them, so the root's 8.11.1
binaries download and launch 9.7.1 here exactly as `forge-example-1.17`'s identical
copies launch 7.6.

**Why 9.7.1 and not 9.6.x:** Loom 1.18.x publishes `runtimeElements` with
`org.gradle.plugin.api-version = 9.7.0`, so a 9.6.1 consumer is rejected at variant
selection and never loads the plugin at all. 9.7.0 is the floor; 9.7.1 is current.

## Mappings

There are none, and none are needed: **MC 26.x ships deobfuscated.** The 26.3 client jar
carries 10,737 real `net/minecraft/…` class names and zero obfuscated ones (1.21.11: 10,201
obfuscated, 33 real), so Mojang publishes no `client_mappings`/`server_mappings` for the
line and Fabric publishes no yarn for it. fabric-meta returns the sentinel
`net.fabricmc:intermediary:0.0.0` — Fabric's identity mappings artifact — for every 26.x
version, and that is what `mappings(...)` takes here.

One consequence: the identity intermediary has no `named` namespace, so Loom cannot remap a
*sources* jar through it, and it tries to for every `modImplementation`. `mcdp-26` is
therefore consumed via plain `implementation`; Fabric's `ClasspathModCandidateFinder`
discovers it from the plain classpath in a dev run. `build.gradle.kts` carries the full
chain inline — it is the authority the rest of the docs quote.

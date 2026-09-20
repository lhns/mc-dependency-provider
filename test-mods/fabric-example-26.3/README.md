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
cd test-mods/fabric-example-26.3 && ./gradlew build        # Gradle 9.6, JDK 25
```

Same shape as `forge-example-1.17` / `forge-example-1.18`: own wrapper, no
`includeBuild("../..")`, `mavenLocal()` first in project `repositories`, and
`cacheChangingModulesFor(0, "seconds")` so the just-published SNAPSHOT is the one
that resolves.

## The wrapper binaries are missing on purpose

`gradle/wrapper/gradle-wrapper.properties` is committed and points at Gradle 9.6.1.
`gradle-wrapper.jar`, `gradlew` and `gradlew.bat` are **not** — they cannot be
produced without a local Gradle 9.6. Regenerate once with `gradle wrapper
--gradle-version 9.6.1`, or have CI use `gradle/actions/setup-gradle@v4` with
`gradle-version: 9.6.1` and call `gradle` instead of `./gradlew`.

## CI status

**Excluded** until the toolchain exists. The blocker is not Minecraft — MC 26.3,
fabric-loader 0.19.5, Fabric API `0.161.0+26.3` and Loom 1.18.2 are all published
and stable. The blocker is that `runserver-smoke-bands` installs JDK 17 + 21 only,
and this cell needs JDK 25 in `setup-java` plus the wrapper above. See
`PHASE4-26.3.md` (root) for the exact matrix row and workflow edits.

## Mappings

There is no yarn for the calendar line — `net.fabricmc:yarn` stops at 1.21.11 and
`meta.fabricmc.net/v2/versions/yarn/26.3` is empty. This mod uses
`loom.officialMojangMappings()`. (The old `fabric-example-26.1` scaffold pinned
`net.fabricmc:yarn:26.1.2+build.1`, which never existed and would not have resolved.)

# neoforge-example-26.2

NeoForge test mod for the single `de.lhns.mcdp:mcdp-26` band (ADR-0032).

## Why the directory says 26.2, not 26.3

**There is no stable NeoForge for MC 26.3.** As of 2026-09-20, five days after the
game released, `maven.neoforged.net/releases/net/neoforged/neoforge/` carries only
`26.3.0.0-beta` … `26.3.0.7-beta`. The newest stable build in the calendar line is
`26.2.0.88`. Booting a beta loader in CI turns NeoForge's pre-release churn into
mcdp red builds, so this cell pins the stable one.

Coverage does not suffer. The NeoForge SPI mcdp implements is byte-identical across
the line: fancymodloader **11.0.16** (pulled by 26.2.0.88) and **12.0.0** (pulled by
26.3.0.7-beta) ship the same `IModLanguageLoader`, `ModContainer`, `IModInfo` and
`ModFileScanData` class bytes — verified by SHA-1. Booting 26.2 exercises the 26.3
surface exactly. Re-pin `neoForge.version` to `26.3.0.x` the day it goes stable.

## How to build it

```sh
cd ../.. && ./gradlew :mcdp-26:publishToMavenLocal :gradle-plugin:publishToMavenLocal
cd test-mods/neoforge-example-26.2 && ./gradlew build      # this dir's own Gradle 9.7.1, JDK 25
```

No `includeBuild("../..")`, own Gradle 9.7.1 wrapper (properties **and** binaries
committed, copied from the repo-root wrapper — they are version-agnostic
bootstrappers), `mavenLocal()` first, `cacheChangingModulesFor(0, "seconds")` — the
`forge-example-1.17`/`-1.18` shape. 9.7.1 matches `fabric-example-26.3` so the two
26.x cells share one downloaded distribution.

**A cheaper path may exist.** Unlike Loom, ModDevGradle 2.0.147 requires only Gradle
>= 8.8 and a daemon Java >= 17, and resolves the run JVM through Gradle's Java
toolchain rather than checking the daemon JVM — so this mod may well build
composite-included on the root Gradle 8.11.1 / JDK 21 daemon with nothing but
`toolchain { languageVersion = 25 }`. That is unverified (no build was run). If it
holds, drop the wrapper and restore `includeBuild("../..")`.

## CI status

This cell is now a row in `runserver-smoke-bands` (`daemon_jdk: "25"`, `25` added to
the job's `setup-java` list before `21`). **It has not yet passed** — no nightly has
run it, so whether the mcdp Gradle plugin even loads on a Gradle 9.x daemon is still
unproven.

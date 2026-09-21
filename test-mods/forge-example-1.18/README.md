# forge-example-1.18

Forge test mod for **MC 1.18.2**, targeting `de.lhns.mcdp:mcdp-1.18`.

In the nightly `runserver-smoke-bands` matrix (`forge-1.18`). **That cell has not yet
passed** — no nightly has produced a green result for it — so this band is *wired*, not
*proven*.

**An ordinary composite-included test mod**, exactly like `forge-example-1.20`: it uses
ForgeGradle 6 through `plugins { id("net.minecraftforge.gradle") version "[6.0,6.2)" }`,
`includeBuild("../..")` in `settings.gradle.kts`, and the repo-root Gradle 8.11.1
wrapper. Build and boot it from the repo root:

```
./gradlew -p test-mods/forge-example-1.18 build
./gradlew -p test-mods/forge-example-1.18 runServer
```

(or `../../gradlew build` from inside this directory.) `toolchain { languageVersion = 17 }`
is resolved as a Gradle *toolchain*, so the daemon can stay on JDK 21 as long as a JDK 17
is installed or provisionable.

## What changed, and why the old scaffold was wrong

This mod used to carry its own `gradlew` pinning **Gradle 7.6**, omit
`includeBuild("../..")`, and consume `mcdp-1.18` plus the `de.lhns.mcdp` plugin from
`mavenLocal()` + a Sonatype snapshot repo. The justification was:

> ForgeGradle 5.1 is the only FG line supporting MC ≤ 1.18, and FG 5.1 rejects Gradle 8.

That is **false**. Forge regenerated the MDKs onto FG6 on both sides of 1.17:

- `forge-1.18.2-40.3.12-mdk.zip` → `id 'net.minecraftforge.gradle' version '[6.0,6.2)'`,
  **Gradle 8.8** wrapper.
- `forge-1.16.5-36.2.42-mdk.zip` → same FG range, **Gradle 8.4** wrapper.
- ForgeGradle 6.0.54's `EnvironmentChecks.checkEnvironment` accepts Gradle `[8.1, 9.0)`;
  the root wrapper is 8.11.1.

1.17 is the one MC version in this repo for which no FG6 MDK was ever published, so
`forge-example-1.17` keeps the old shape. See [`../README.md`](../README.md) and
ADR-0023's errata.

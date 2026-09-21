# forge-example-1.18

Forge test mod for **MC 1.18.2**, targeting `de.lhns.mcdp:mcdp-1.18`.

Green in the nightly `runserver-smoke-bands` matrix (`forge-1.18`) and in the Tier-3
`runClient` nightly.

**An ordinary composite-included test mod**, exactly like `forge-example-1.20`: ForgeGradle 6
through `plugins { id("net.minecraftforge.gradle") version "[6.0,6.2)" }`,
`includeBuild("../..")` in `settings.gradle.kts`, and the repo-root Gradle 8.11.1 wrapper —
the official `forge-1.18.2-40.3.12-mdk.zip` is itself FG6 on a Gradle 8.8 wrapper, and FG
6.0.54 accepts Gradle `[8.1, 9.0)`. Build and boot it from the repo root:

```
./gradlew -p test-mods/forge-example-1.18 build
./gradlew -p test-mods/forge-example-1.18 runServer
```

(or `../../gradlew build` from inside this directory.) `toolchain { languageVersion = 17 }`
is resolved as a Gradle *toolchain*, so the daemon can stay on JDK 21 as long as a JDK 17
is installed or provisionable.

1.17 is the one MC version in this repo for which no FG6 MDK was ever published, so
`forge-example-1.17` keeps its own Gradle 7.6 wrapper. See [`../README.md`](../README.md)
and ADR-0023's errata.

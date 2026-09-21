# forge-example-1.17

Forge test mod for **MC 1.17.1**, targeting `de.lhns.mcdp:mcdp-1.17`.

In the nightly `runserver-smoke-bands` matrix (`forge-1.17`, `daemon_jdk: "17"`), on both
OSes. There is no Tier-3 `runClient` cell — that one exclusion, and its reasoning, is in
`.github/workflows/mc-client-nightly.yml`'s header.

The band is **not** a stub. Forge 1.17.1-37.1.2 requires `forgespi 4.0.+`, so `forge-1.17`
compiles `forge-1.18`'s adapter source verbatim and pins only its own coordinates and the
Java 16 target ([ADR-0029](../../docs/adr/0029-forge-1-17-shares-the-4-0-adapter.md)). The
mcdp Gradle plugin works here too: it targets Java 17 bytecode, inside Gradle 7.6's Java
8–19 window (ADR-0023's errata).

**The committed Gradle 7.6 wrapper here is load-bearing**, not leftover: ForgeGradle 5.1
rejects Gradle 8.x, and 1.17 is the one MC version Forge never regenerated an FG6 MDK for.
(`forge-example-1.18` did get one and moved to FG6 on the root wrapper; whether the same
migration works here is untested.) For the same reason `settings.gradle.kts` omits
`includeBuild("../..")` — the parent build needs Gradle 8 — so run
`../../gradlew :mcdp-1.17:publishToMavenLocal` first, then `./gradlew build` from here.

The JDK split follows from that: the **daemon** is JDK 17 (Gradle 7.6 cannot run on 21),
while `toolchain { languageVersion = 16 }` is resolved as a Gradle toolchain. `setup-java`
installs Adoptium's `jdk-16.0.2+7` for it.

See [`../README.md`](../README.md) and ADR-0023.

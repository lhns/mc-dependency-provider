# forge-example-1.17

Forge test mod for **MC 1.17.1**, targeting `de.lhns.mcdp:mcdp-1.17`.

**Deliberately not in CI.** ForgeGradle 5.1 (the only FG line supporting MC ≤ 1.18)
forces Gradle 7 / Java ≤ 19, while mcdp's `gradle-plugin` compiles to Java 21
bytecode — so the mcdp plugin path is unusable on this band. The `mcdp-1.17`
*runtime* works; only manifest generation must happen outside mcdp. This band also
still has a **stub adapter** (forgespi 3.2.x `loadMod` predates `ModuleLayer`;
`IModFileInfo.getFile()` is missing) — the real port is a follow-up.

**The committed Gradle 7.6 wrapper here is load-bearing**, not leftover: ForgeGradle
5.1 rejects Gradle 8.x, so this build must run on its own `./gradlew` rather than
the repo-root 8.11.1 one. For the same reason `settings.gradle.kts` omits
`includeBuild("../..")` — run `../../gradlew :mcdp-1.17:publishToMavenLocal` first,
then build here.

See [`../README.md`](../README.md) and ADR-0023 ("Consequences — negative"); the
exclusion is also recorded in the "Excluded from coverage" comment in
`.github/workflows/mc-smoke.yml`.

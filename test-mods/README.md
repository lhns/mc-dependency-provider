# test-mods

Twenty standalone example mods that exercise the full mcdp stack end to end.

Each directory is its **own** Gradle build (Loom, ModDevGradle or ForgeGradle) that
pulls mcdp in through composite-build `includeBuild("../..")` — they are not
subprojects of the root build, so `./gradlew :test-mods:…` does not reach them.
Build one with `cd test-mods/<mod> && ../../gradlew build`, or all of them with
`scripts/test-mods.sh <gradle-args>`.

Thirteen of the twenty run in CI. The other seven are **not abandoned code**: three
are blocked on the build toolchain and four are simply not wired in yet. Both
reasons are detailed below, and mirrored in the "Excluded from coverage" comment in
`.github/workflows/mc-smoke.yml`.

## Coverage

The **CI** column says what actually runs. "not in CI" means no run has ever
exercised that mod — read it as *unproven*, not *known broken*.

| Mod | Loader / MC band | Lang | CI |
|---|---|---|---|
| `fabric-example` | Fabric 1.21.1 | scala | `runserver-smoke` (push + nightly), `mc-client-nightly`, `version-isolation-fabric` |
| `fabric-example-v2` | Fabric 1.21.1 | scala | `version-isolation-fabric` (second mod, conflicting stdlib version) |
| `neoforge-example` | NeoForge 21.1 / MC 1.21.1 | scala | `runserver-smoke` (push + nightly), `mc-client-nightly` |
| `mixin-example` | Fabric 1.21.1 | scala | `mixin-codegen-smoke` |
| `kotlin-example` | NeoForge 21.1 / MC 1.21.1 | kotlin | `ci.yml` build, `modcontainer-smoke`, `scripts/test-mods.sh` |
| `scala-example` | no loader — manifest/jar only | scala | `ci.yml` build (Tier-1 manifest smoke) |
| `fabric-example-1.17` | Fabric 1.17.1 | java | `runserver-smoke-bands` (nightly) |
| `fabric-example-1.18` | Fabric 1.18.2 | java | `runserver-smoke-bands` (nightly) |
| `fabric-example-1.19` | Fabric 1.19.2 (loader 0.14.25) | java | **not in CI** — new today, never built (below) |
| `forge-example-1.19` | Forge 1.19.2-43.5.2 | java | **not in CI** — new today, never built (below) |
| `fabric-example-1.20` | Fabric 1.20.1 | java | `runserver-smoke-bands` (nightly) |
| `forge-example-1.20` | Forge 1.20.1 | java | `runserver-smoke-bands` (nightly) |
| `fabric-example-1.20.6` | Fabric 1.20.6 | java | `runserver-smoke-bands` (nightly) |
| `neoforge-example-1.20.6` | NeoForge 20.6 / MC 1.20.6 | java | `runserver-smoke-bands` (nightly) |
| `fabric-example-1.21.11` | Fabric 1.21.11 (loader 0.19.5) | java | **not in CI** — new today, never built (below) |
| `neoforge-example-1.21.11` | NeoForge 21.11.45 / MC 1.21.11 | java | **not in CI** — new today, never built (below) |
| `fabric-example-26.3` | Fabric / MC 26.3 | java | **No cell** — no Mojang mappings and no yarn exist for any 26.x release; see below |
| `neoforge-example-26.2` | NeoForge 26.2.0.88 / MC 26.2 | java | `runserver-smoke-bands` (nightly) — cell exists but **has not yet passed** |
| `forge-example-1.17` | Forge 1.17.1 | java | **excluded** — toolchain (no JDK 16 on runners; below) |
| `forge-example-1.18` | Forge 1.18.2 | java | `runserver-smoke-bands` (nightly) — cell exists but **has not yet passed**; ported to ForgeGradle 6 / root wrapper |

## The four new mods: never built

`fabric-example-1.19`, `forge-example-1.19`, `fabric-example-1.21.11` and
`neoforge-example-1.21.11` were added alongside the `mcdp-1.19` and `mcdp-1.21.11`
bands ([ADR-0031](../docs/adr/0031-mc-1-19-band.md),
[ADR-0030](../docs/adr/0030-mc-1-21-11-band.md)). No build, no boot, no CI cell —
their pins are researched, not verified. All four use the ordinary composite-build
shape (`includeBuild("../..")`, no own wrapper), so adding them is a matrix-row
change once someone has run them locally:

- **1.19 pair** — nothing known blocks them. `fabric-example-1.19` is Loom 1.9 on
  MC 1.19.2 / fabric-loader 0.14.25; `forge-example-1.19` is ForgeGradle 6.0.+ on
  Forge 1.19.2-43.5.2, which runs on the parent's Gradle 8 daemon (unlike the FG
  5.1 mods below), with a JDK 17 toolchain.
- **1.21.11 pair** — `neoforge-example-1.21.11` is MDG 2.0.147 / NeoForge 21.11.45,
  straightforward. `fabric-example-1.21.11` has **one unverified pin**: the repo
  wrapper is Gradle 8.11.1, so the newest Loom usable through `../../gradlew` is
  the 1.9 line (1.10+ needs Gradle 8.12), and whether Loom 1.9 can provision MC
  1.21.11 has not been confirmed. If it cannot, the fix is a root wrapper bump to
  >= 8.12 plus a current Loom (1.18.2), or a committed per-mod wrapper the way
  `forge-example-1.17` carries its own. The same note is in that mod's
  `build.gradle.kts`.

## Why the others are excluded

### `fabric-example-26.3`, `neoforge-example-26.2`

Both target the single **`mcdp-26`** band, which covers Mojang's whole
calendar-versioning line (26.1 / 26.2 / 26.3) — see
[ADR-0032](../docs/adr/0032-single-26x-band.md). They replace the old
`fabric-example-26.1` / `neoforge-example-26.1` scaffolds.

**The old exclusion reason — "MC 26.1.x is scaffold-only, Mojang has not shipped
real artifacts" — is obsolete.** 26.1, 26.2 and 26.3 are all in the version manifest
with real downloads (26.3 released 2026-09-15), and fabric-loader 0.19.5, Fabric API
`0.161.0+26.3` and Loom 1.18.2 are published and stable. What blocks the **NeoForge**
cell is **the build toolchain, not Minecraft** (the Fabric one is blocked by missing
mappings — see the end of this section): MC 26.1+ declare
`javaVersion.majorVersion = 25`; Loom refuses a MC version whose required Java
exceeds the **Gradle daemon** JVM (`26.1.2 requires Java 25 but Gradle is using 21`);
and Gradle 8.11.1, the repo root, cannot run on JDK 25 at all.

So these two mods take the `forge-example-1.17` / `-1.18` shape in the other
direction: their **own Gradle 9.7.1 wrapper**, no `includeBuild("../..")`,
`mavenLocal()` first in project `repositories`, and `cacheChangingModulesFor(0,
"seconds")`. The full wrapper — `gradlew`, `gradlew.bat` and
`gradle/wrapper/gradle-wrapper.jar` — **is committed**, copied from the root wrapper.
(An earlier note here claimed the binaries "cannot be generated without a local Gradle
9.6.1". That was wrong: the wrapper jar and launcher scripts are version-agnostic
bootstrappers that read `distributionUrl` from the properties file beside them. The
root and `forge-example-1.17` jars are byte-identical yet launch 8.11.1 and 7.6
respectively.)

**9.7.1, not 9.6.x**, and the reason is not the daemon JDK: Loom 1.18.x
(1.18.0-alpha.23 / 1.18.1 / 1.18.2) publishes `runtimeElements` with
`org.gradle.plugin.api-version = 9.7.0` and `org.gradle.jvm.version = 25`, so a 9.6.1
consumer is rejected at *variant selection* and never even loads the plugin. 9.7.0 is
the hard floor; 9.7.1 is the current release.

The repo-root wrapper stays at **8.11.1** and must not be bumped: ForgeGradle 6.0.54's
`EnvironmentChecks.checkEnvironment` accepts Gradle `[8.1, 9.0)`, and
`forge-example-1.19` / `-1.20` composite-include the root build.

`neoforge-example-26.2` is a row in `runserver-smoke-bands` with `daemon_jdk: "25"`, and
**25** is in the workflow's `setup-java` list (before `21`, which stays last so it keeps
winning `JAVA_HOME` for every other cell).

**Both open questions were answered by run 35545861369, and one of them badly.**

*The mcdp Gradle plugin does load on a Gradle 9.7.1 daemon.* Both cells got past plugin
resolution and configuration; the NeoForge one reached `:generateMcdpBridges` and failed
there on `Unsupported class file major version 69` — the bundled ASM predated Java 25.
Fixed by pinning ASM 9.10.1, with `ClassFileVersionSupportTest` to keep it fixed.

*`fabric-example-26.3` has no CI cell, and the blocker is upstream.* Loom got as far as
mapping resolution and stopped: **Mojang publishes no `client_mappings`/`server_mappings`
for any 26.x release**, and **Fabric has no yarn builds for the line either** —
`meta.fabricmc.net/v2/versions/yarn/26.1|26.2|26.3` all return `[]`. Compare 1.21.11,
whose version JSON carries both mapping downloads. So Loom has no mapping source at all,
for any 26.x version, with any mappings setting. This is not a toolchain problem and
nothing in this repo can fix it; the Fabric half of `mcdp-26` stays compile-only until
upstream publishes mappings. The mod keeps its 9.7.1 wrapper — that part is correct and
was verified in CI.

`neoforge-example-26.2` pins MC 26.2 / NeoForge **26.2.0.88** rather than 26.3 because
**NeoForge has no stable 26.3** — that line is `26.3.0.0-beta` … `26.3.0.7-beta`.
Coverage is unaffected: the SPI is byte-identical across fancymodloader 11.0.15,
11.0.16 and 12.0.0 (ADR-0032). Its constraint is weaker than the Fabric cell's:
ModDevGradle 2.0.147 requires only Gradle >= 8.8 and a daemon Java >= 17, and picks the
run JVM via toolchains — it has no Loom-style daemon-JVM check. It carries the same
9.7.1 wrapper anyway so the two 26.x cells share one distribution; whether it could
instead stay composite-included on the root 8.11.1 daemon with just
`toolchain { languageVersion = 25 }` is still untested.

### `forge-example-1.17` (the last FG 5.1 mod)

**Correction to what this section used to say.** "ForgeGradle 5.1 is the only FG line
that supports MC ≤ 1.18" is **false**. Forge regenerated its MDKs onto FG6 on both
sides of 1.17: `forge-1.18.2-40.3.12-mdk.zip` declares
`id 'net.minecraftforge.gradle' version '[6.0,6.2)'` with a **Gradle 8.8** wrapper, and
`forge-1.16.5-36.2.42-mdk.zip` is FG6 / **Gradle 8.4**. FG 6.0.54's
`EnvironmentChecks.checkEnvironment` accepts Gradle `[8.1, 9.0)`, and the root wrapper
is 8.11.1 — inside that window. So FG6 handles MC ≤ 1.18 userdev configs fine.

`forge-example-1.18` has been ported to that shape: FG6 through `plugins { id(...) }`,
`includeBuild("../..")`, the root wrapper, no `mavenLocal()`, no wrapper of its own.
It is an ordinary composite-included test mod now, exactly like `forge-example-1.20`.
**Its cell has not yet passed** — it has never produced a green nightly — so MC
1.18.2-on-Forge is *wired*, not *proven*.

`forge-example-1.17` is the one mod left on the FG 5.1 / Gradle 7.6 shape, because 1.17
is the one MC version Forge never regenerated an MDK for. Whether the same FG6 migration
works there is **untested**; it also has an independent JDK-16 constraint (below).

**The mcdp plugin path works on these bands, and so does the Forge adapter.** This
section also once claimed the *plugin* did not work under Gradle 7. Half of that was
real — `:gradle-plugin` published `org.gradle.jvm.version=21`, which a Gradle 7.6
daemon rejects outright — and is fixed: the plugin now targets **Java 17**. The other
half, "uses Gradle 8 APIs", was simply false. See ADR-0023's errata for the audit.

`forge-example-1.17` is not in the matrix at all, but no longer because of the
adapter: that band was long believed stuck on forgespi 3.2.x, and it isn't — Forge
1.17.1-37.1.2 requires `forgespi 4.0.+`, so `forge-1.17` shares the same adapter
source as `forge-1.18` (and `forge-1.19` / `forge-1.20`, which share it too — see
[ADR-0029](../docs/adr/0029-forge-1-17-shares-the-4-0-adapter.md)). The one thing
still holding the row back is toolchain: it needs a JDK **16** in the workflow's
`setup-java` list, because `forge-example-1.17` pins
`toolchain { languageVersion = 16 }` and GitHub runners ship no JDK 16.

(ADR-0023 once noted that the `mcdp-1.17` aggregator left `FMLModType` unset because
its adapter was a stub. That is stale: the aggregator now emits
`FMLModType: LANGPROVIDER` like every other Forge/NeoForge band.)

**Forge caveat that applies to all four Forge bands:** `@EventBusSubscriber`
auto-registration is not implemented on Forge — it is NeoForge-only (ADR-0027/0028).
Forge test mods register subscribers explicitly.

### The Gradle 7.6 wrapper in `forge-example-1.17`

This mod commits a full wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) pinning
**Gradle 7.6**, while the repo root wrapper is 8.11.1. (The two MC 26.x mods above carry
a full wrapper too, pinning Gradle 9.7.1 — same pattern, opposite direction.) It is
load-bearing *for 1.17 specifically*:

- Forge published no FG6 MDK for 1.17, so this mod is still on ForgeGradle 5.1, which
  rejects Gradle 8.x outright (`EnvironmentChecks.checkGradleRange`).
- Consequently its `settings.gradle.kts` also omits `includeBuild("../..")` — the
  parent build needs Gradle 8 (Shadow 8.3.5), so it cannot be composite-included
  from a Gradle 7 build. Build it with `./gradlew` *from inside the mod directory*,
  after `../../gradlew :mcdp-1.17:publishToMavenLocal` has populated mavenLocal.

`forge-example-1.18` used to be in this list on the (false) premise that FG 5.1 was the
only option for MC ≤ 1.18. It is not: it runs FG6 on the root wrapper now, so its
wrapper, its `mavenLocal()` repos, its snapshot repo, its explicit `de.lhns.mcdp`
version pin and its `cacheChangingModulesFor(0, "seconds")` block are all gone.

Every other test-mod — including the four new 1.19 / 1.21.11 mods — intentionally has
no wrapper at all and is driven by `../../gradlew` through `includeBuild("../..")`.

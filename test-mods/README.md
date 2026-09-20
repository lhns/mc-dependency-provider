# test-mods

Twenty standalone example mods that exercise the full mcdp stack end to end.

Each directory is its **own** Gradle build (Loom, ModDevGradle or ForgeGradle) that
pulls mcdp in through composite-build `includeBuild("../..")` — they are not
subprojects of the root build, so `./gradlew :test-mods:…` does not reach them.
Build one with `cd test-mods/<mod> && ../../gradlew build`, or all of them with
`scripts/test-mods.sh <gradle-args>`.

Thirteen of the twenty run in CI. The other seven are **not abandoned code**, and
they are not all excluded for the same reason:

- **Three are blocked on the build toolchain** — `fabric-example-26.3`,
  `neoforge-example-26.2` and `forge-example-1.17`. Their adapters work; what is
  missing is a Gradle daemon / JDK combination CI can provide today. Reasons are
  recorded below and in the "Excluded from coverage" comment in
  `.github/workflows/mc-smoke.yml`.
- **Four are new and simply not wired in yet** — `fabric-example-1.19`,
  `forge-example-1.19`, `fabric-example-1.21.11` and `neoforge-example-1.21.11`.
  These four have **never been built or booted**. Nothing is known to block the
  1.19 pair; the 1.21.11 pair has one open question (Loom 1.9 vs MC 1.21.11, see
  below).

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
| `fabric-example-26.3` | Fabric / MC 26.3 | java | **excluded** — toolchain, not Minecraft (below) |
| `neoforge-example-26.2` | NeoForge 26.2.0.88 / MC 26.2 | java | **excluded** — toolchain, not Minecraft (below) |
| `forge-example-1.17` | Forge 1.17.1 | java | **excluded** — toolchain (no JDK 16 on runners; below) |
| `forge-example-1.18` | Forge 1.18.2 | java | `runserver-smoke-bands` (nightly) — cell exists but **has not yet passed** |

## The four new mods: never built

`fabric-example-1.19`, `forge-example-1.19`, `fabric-example-1.21.11` and
`neoforge-example-1.21.11` were added today alongside the `mcdp-1.19` and
`mcdp-1.21.11` bands ([ADR-0031](../docs/adr/0031-mc-1-19-band.md),
[ADR-0030](../docs/adr/0030-mc-1-21-11-band.md)). No build, no boot, no CI cell.
Their pins are researched, not verified.

All four use the ordinary composite-build shape (`includeBuild("../..")`, no own
wrapper), so adding them is a matrix-row change once someone has run them locally:

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
  `forge-example-1.18` carries its own. The same note is in that mod's
  `build.gradle.kts`.

## Why the others are excluded

### `fabric-example-26.3`, `neoforge-example-26.2`

Both target the single **`mcdp-26`** band, which covers Mojang's whole
calendar-versioning line (26.1 / 26.2 / 26.3) — see
[ADR-0032](../docs/adr/0032-single-26x-band.md), which supersedes ADR-0023's
`mcdp-26.1` row. They replace the old `fabric-example-26.1` / `neoforge-example-26.1`
scaffolds.

**The old exclusion reason is obsolete.** This section used to say MC 26.1.x was
"scaffold-only: Mojang has not shipped real artifacts". Mojang has: 26.1, 26.2 and
26.3 are all in the version manifest with real downloads, 26.3 released 2026-09-15,
and fabric-loader 0.19.5, Fabric API `0.161.0+26.3` and Loom 1.18.2 are all published
and stable.

What actually blocks these two cells is **the build toolchain, not Minecraft**:

- MC 26.1+ all declare `javaVersion.majorVersion = 25`.
- Loom refuses a MC version whose required Java exceeds the **Gradle daemon** JVM —
  that is exactly the `26.1.2 requires Java 25 but Gradle is using 21` error.
- Gradle 8.11.1 (the repo root) cannot run on JDK 25 at all.

So these two mods take the `forge-example-1.17` / `-1.18` shape in the other
direction: their **own Gradle 9.6.1 wrapper**, no `includeBuild("../..")`,
`mavenLocal()` first in project `repositories`, and `cacheChangingModulesFor(0,
"seconds")`. Their `gradle/wrapper/gradle-wrapper.properties` is committed; the
wrapper **jar and scripts are not**, because they cannot be generated without a local
Gradle 9.6.1 — regenerate with `gradle wrapper --gradle-version 9.6.1`, or have CI use
`gradle/actions/setup-gradle@v4`. The workflow's `setup-java` list also needs **JDK
25** added.

`neoforge-example-26.2` pins MC 26.2 / NeoForge **26.2.0.88** rather than 26.3 because
**NeoForge has no stable 26.3** — that line is `26.3.0.0-beta` … `26.3.0.7-beta`.
Coverage is unaffected: the SPI is byte-identical across fancymodloader 11.0.15,
11.0.16 and 12.0.0 (ADR-0032). It is also worth checking whether that mod needs the
standalone shape at all — unlike Loom, ModDevGradle picks its run JVM via Gradle
toolchains, so it may build composite-included on the root Gradle 8.11.1 daemon with
just `toolchain { languageVersion = 25 }`.

### `forge-example-1.17` (and the FG 5.1 shape it shares with `forge-example-1.18`)

ForgeGradle 5.1 is the only FG line that supports MC ≤ 1.18, and it forces Gradle 7
(Java ≤ 19), so these two mods run on their own Gradle 7.6 wrapper (see below).

**The mcdp plugin path works on these bands, and so does the Forge adapter.** This
section previously claimed the *plugin* did not work, for two reasons; only one of
them was ever real:

- *"compiles to Java 21 bytecode"* — this was true and was the actual blocker. A
  Gradle 7.6 daemon runs on at most JDK 19, so it rejected the published
  `gradle-plugin` variant (`org.gradle.jvm.version=21`) outright. Fixed:
  `:gradle-plugin` now targets **Java 17** (root `build.gradle.kts`). 17 rather than
  16 because `RunTaskClasspathPatch` uses `java.util.HexFormat`, a Java 17 API — and
  16 buys nothing here anyway, since the plugin's bytecode is loaded by the *Gradle
  daemon*, never by Minecraft's JVM. (That MC-runtime floor is why `core` and
  `deps-lib` target 16; it does not apply to a build-time plugin.)
- *"against Gradle 8 APIs"* — **this was false.** Every `org.gradle.*` type the
  plugin imports (`Plugin`, `Project`, `Configuration`, `ResolvedArtifact`,
  `MavenArtifactRepository`, `RegularFileProperty`, `ListProperty`,
  `JavaPluginExtension`, `SourceSet`, `JavaExec`, `ProcessResources`, `ProjectLayout`,
  `DuplicatesStrategy`, the task-annotation set) exists in Gradle 7.6, and the source
  uses none of Gradle 8's added surface — no `consumable()`/`resolvable()`
  configuration factories, no `Problems` API, no `DependencyCollector`, no
  `MapProperty`, no `Provider.zip`. Nothing in the plugin needed changing.

`forge-example-1.18` is therefore in the nightly `runserver-smoke-bands` matrix.
**That cell has not yet passed** — it was added along with the Java-17 plugin
target and no nightly has produced a green result for it, so MC 1.18.2-on-Forge is
*wired*, not *proven*.

`forge-example-1.17` is not in the matrix at all, but no longer because of the
adapter. That band was long believed to be stuck on forgespi 3.2.x, whose `loadMod`
predates `ModuleLayer` and whose `IModFileInfo` lacks `getFile()`. It isn't: Forge
1.17.1-37.1.2 requires `forgespi 4.0.+`, so `forge-1.17` shares the same adapter
source as `forge-1.18` (and `forge-1.19` / `forge-1.20`, which share it too — see
[ADR-0029](../docs/adr/0029-forge-1-17-shares-the-4-0-adapter.md)). The one thing
still holding the row back is toolchain: it needs a JDK **16** in the workflow's
`setup-java` list, because `forge-example-1.17` pins
`toolchain { languageVersion = 16 }` and GitHub runners ship no JDK 16.

(ADR-0023 once noted that the `mcdp-1.17` aggregator left `FMLModType` unset because
its adapter was a stub. That is stale: the aggregator now emits
`FMLModType: LANGPROVIDER` like every other Forge/NeoForge band.)

**Forge caveat that applies to all four Forge bands:** the adapter has lifecycle
events, mixin bridges, stdlib promotion and download progress (ADR-0027/0028), but
**`@EventBusSubscriber` auto-registration is not implemented on Forge** — that is
NeoForge-only. Forge test mods register subscribers explicitly.

### The Gradle 7.6 wrappers in `forge-example-1.17` / `forge-example-1.18`

These two mods commit a full wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`)
pinning **Gradle 7.6**, while the repo root wrapper is 8.11.1. (The two MC 26.x mods
above also carry wrapper *properties*, pinning Gradle 9.6.1 — same pattern, opposite
direction, and without the binaries.) That is **load-bearing, not cruft**:

- ForgeGradle 5.1 rejects Gradle 8.x outright (`EnvironmentChecks.checkGradleRange`),
  so these builds must run on a Gradle 7 distribution.
- Consequently their `settings.gradle.kts` also omit `includeBuild("../..")` — the
  parent build needs Gradle 8 (Shadow 8.3.5), so it cannot be composite-included
  from a Gradle 7 build. Build them with `./gradlew` *from inside the mod
  directory*, after `../../gradlew :mcdp-1.17:publishToMavenLocal` (resp.
  `:mcdp-1.18:…`) has populated mavenLocal.

Every other test-mod — including the four new 1.19 / 1.21.11 mods — intentionally has
no wrapper at all and is driven by `../../gradlew` through `includeBuild("../..")`.
The only mods with wrapper files of any kind are `forge-example-1.17` /
`forge-example-1.18` (full Gradle 7.6 wrapper) and `fabric-example-26.3` /
`neoforge-example-26.2` (Gradle 9.6.1 wrapper *properties* only).

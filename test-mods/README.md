# test-mods

Twenty-one standalone example mods that exercise the full mcdp stack end to end.

Each directory is its **own** Gradle build (Loom, ModDevGradle or ForgeGradle) that
pulls mcdp in through composite-build `includeBuild("../..")` — they are not
subprojects of the root build, so `./gradlew :test-mods:…` does not reach them.
Build one with `cd test-mods/<mod> && ../../gradlew build`, or all of them with
`scripts/test-mods.sh <gradle-args>`.

Three mods are the exception and carry a committed wrapper of their own:
`forge-example-1.17` (Gradle 7.6) and the two MC 26.x mods (Gradle 9.7.1). Both cases are
explained below.

## Coverage

Every mod here runs in CI, with one exception noted in the table. The workflows are
canonical — this table follows their matrices, not the other way round.

| Mod | Loader / MC band | Lang | CI |
|---|---|---|---|
| `fabric-example` | Fabric 1.21.1 | scala | `runserver-smoke` (push + nightly), `runclient-smoke`, `version-isolation-fabric` |
| `fabric-example-v2` | Fabric 1.21.1 | scala | `version-isolation-fabric` (second mod, conflicting stdlib version) |
| `neoforge-example` | NeoForge 21.1 / MC 1.21.1 | scala | `runserver-smoke` (push + nightly, incl. the NeoForge mixin-bridge assertion), `runclient-smoke` |
| `mixin-example` | Fabric 1.21.1 | scala | `mixin-codegen-smoke` |
| `shared-mixin-example` | Fabric 1.21.1 | java | not yet wired — see the mod's `README.md` |
| `kotlin-example` | NeoForge 21.1 / MC 1.21.1 | kotlin | `ci.yml` build, `modcontainer-smoke`, `scripts/test-mods.sh` |
| `scala-example` | no loader — manifest/jar only | scala | `ci.yml` build (Tier-1 manifest smoke) |
| `fabric-example-1.17` | Fabric 1.17.1 | java | `runserver-smoke-bands`, `runclient-smoke` |
| `forge-example-1.17` | Forge 1.17.1 | java | `runserver-smoke-bands` (`daemon_jdk: 17`, JDK 16 toolchain) — **no client cell**, see below |
| `fabric-example-1.18` | Fabric 1.18.2 | java | `runserver-smoke-bands`, `runclient-smoke` |
| `forge-example-1.18` | Forge 1.18.2 | java | `runserver-smoke-bands`, `runclient-smoke` |
| `fabric-example-1.19` | Fabric 1.19.2 (loader 0.14.25) | java | `runserver-smoke-bands`, `runclient-smoke` |
| `forge-example-1.19` | Forge 1.19.2-43.5.2 | java | `runserver-smoke-bands`, `runclient-smoke` |
| `fabric-example-1.20` | Fabric 1.20.1 | java | `runserver-smoke-bands`, `runclient-smoke` |
| `forge-example-1.20` | Forge 1.20.1 | java | `runserver-smoke-bands`, `runclient-smoke` (the one cell asserting `dist=CLIENT` and a client-side mixin) |
| `fabric-example-1.20.6` | Fabric 1.20.6 | java | `runserver-smoke-bands`, `runclient-smoke` |
| `neoforge-example-1.20.6` | NeoForge 20.6 / MC 1.20.6 | java | `runserver-smoke-bands` (incl. its mixin-bridge assertion), `runclient-smoke` |
| `fabric-example-1.21.11` | Fabric 1.21.11 (loader 0.19.5) | java | `runserver-smoke-bands`, `runclient-smoke` |
| `neoforge-example-1.21.11` | NeoForge 21.11.45 / MC 1.21.11 | java | `runserver-smoke-bands`, `runclient-smoke` |
| `fabric-example-26.3` | Fabric / MC 26.3 | java | `runserver-smoke-bands`, `runclient-smoke` (`daemon_jdk: 25`) |
| `neoforge-example-26.2` | NeoForge 26.2.0.88 / MC 26.2 | java | `runserver-smoke-bands`, `runclient-smoke` (`daemon_jdk: 25`) |

`runserver-smoke` and `runserver-smoke-bands` live in `.github/workflows/mc-smoke.yml`
(Tier 2, nightly for the bands, both OSes); `runclient-smoke` is
`.github/workflows/mc-client-nightly.yml` (Tier 3, nightly, Linux + xvfb only). Every cell
asserts the mod's own `[mcdp-smoke] mod=<id>` marker, so "MC booted" cannot pass for
"mcdp ran".

**The one exclusion** is a `runClient` cell for `forge-example-1.17`. It is not a band gap —
the 1.17 band's client path is covered by `fabric-1.17`, and the Forge half has a server
cell. The reasoning is in `mc-client-nightly.yml`'s header.

## The two MC 26.x mods

`fabric-example-26.3` and `neoforge-example-26.2` both target the single **`mcdp-26`** band,
which covers Mojang's whole calendar-versioning line (26.1 / 26.2 / 26.3) — see
[ADR-0032](../docs/adr/0032-single-26x-band.md).

They are the only mods with their **own Gradle 9.7.1 wrapper** (no `includeBuild("../..")`,
`mavenLocal()` first in `pluginManagement` *and* in project `repositories`,
`cacheChangingModulesFor(0, "seconds")`). Four facts pin that shape:

- **Why a separate distribution at all.** MC 26.1+ declare `javaVersion.majorVersion = 25`;
  Loom refuses a MC version whose required Java exceeds the **Gradle daemon** JVM
  (`26.1.2 requires Java 25 but Gradle is using 21`); and Gradle 8.11.1, the repo root,
  cannot run on JDK 25 at all. The root wrapper stays at 8.11.1 and must not be bumped:
  ForgeGradle 6.0.54 accepts Gradle `[8.1, 9.0)`, and the Forge test mods composite-include
  the root build.
- **Why 9.7.1, not 9.6.x** — and the reason is not the daemon JDK. Loom 1.18.x publishes
  `runtimeElements` with `org.gradle.plugin.api-version = 9.7.0`, so a 9.6.1 consumer is
  rejected at *variant selection* and never loads the plugin. 9.7.0 is the hard floor.
- **Why the Fabric mod has no mappings.** MC 26.x ships **deobfuscated**: the 26.3 client jar
  carries 10,737 real `net/minecraft/…` class names and zero obfuscated ones, where 1.21.11
  carries 10,201 obfuscated and 33 real. Mojang publishes no `client_mappings`/`server_mappings`
  for the line and Fabric publishes no yarn for it because there is nothing left to map.
  fabric-meta returns the sentinel `net.fabricmc:intermediary:0.0.0` — Fabric's identity
  mappings artifact — for every 26.x version, and that is what `mappings(...)` takes.
- **Why `mcdp-26` is consumed via plain `implementation`.** Consequence of the line above: the
  identity intermediary has no `named` namespace, so Loom cannot remap a *sources* jar through
  it (`Could not find namespace "named" in provided tiny tree`), and it tries to for every
  `modImplementation`. Nothing needs remapping against an unobfuscated game, and Fabric's
  `ClasspathModCandidateFinder` discovers mcdp from the plain classpath in a dev run.
  `fabric-example-26.3/build.gradle.kts` carries this chain inline and is the authority for it.

`neoforge-example-26.2` pins MC 26.2 / NeoForge **26.2.0.88** rather than 26.3 because
**NeoForge has no stable 26.3** — that line is `26.3.0.0-beta` … `26.3.0.7-beta`, and booting
a beta loader would turn NeoForge's pre-release churn into mcdp red builds. Coverage is
unaffected: the SPI is byte-identical across fancymodloader 11.0.15, 11.0.16 and 12.0.0
(ADR-0032), which `ci.yml`'s `spi-identity` job re-checks on every run. Its toolchain
constraint is also weaker than the Fabric cell's — ModDevGradle 2.0.147 requires only Gradle
>= 8.8 and a daemon Java >= 17, with no Loom-style daemon-JVM check — so it may not need the
wrapper at all; it carries the same 9.7.1 one so the two cells share a distribution. Untested.

## The Gradle 7.6 wrapper in `forge-example-1.17`

This mod commits a full wrapper pinning **Gradle 7.6**, while the repo root wrapper is
8.11.1. It is load-bearing *for 1.17 specifically*:

- Forge published no FG6 MDK for 1.17 — 1.16.5 and 1.18.2 both got one — so this mod is
  still on ForgeGradle 5.1, which rejects Gradle 8.x outright
  (`EnvironmentChecks.checkGradleRange`). Whether an FG6 migration would work here anyway,
  with no MDK to copy from, is untested.
- Consequently its `settings.gradle.kts` also omits `includeBuild("../..")` — the parent
  build needs Gradle 8 (Shadow 8.3.5), so it cannot be composite-included from a Gradle 7
  build. Build it with `./gradlew` *from inside the mod directory*, after
  `../../gradlew :mcdp-1.17:publishToMavenLocal` has populated mavenLocal.
- Its CI cell therefore runs `daemon_jdk: "17"` (Gradle 7.6 runs on Java 8–19) with
  `toolchain { languageVersion = 16 }` provisioned from `setup-java`'s `16` entry. It has no
  foojay resolver in `settings.gradle.kts`, which is why that entry has to be there.

The mcdp Gradle plugin is usable on this band: it targets **Java 17** bytecode, inside Gradle
7.6's window (ADR-0023's errata). The `forge-1.17` adapter is not a stub either — Forge
1.17.1-37.1.2 requires `forgespi 4.0.+`, so the band shares `forge-1.18`'s source
([ADR-0029](../docs/adr/0029-forge-1-17-shares-the-4-0-adapter.md)).

Every other test mod, including `forge-example-1.18`, intentionally has no wrapper at all and
is driven by `../../gradlew` through `includeBuild("../..")`.

## Forge caveat, all four Forge bands

`@EventBusSubscriber` auto-registration is not implemented on Forge — it is NeoForge-only
(ADR-0027 / ADR-0028). Forge test mods register subscribers explicitly.

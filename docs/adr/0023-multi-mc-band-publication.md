# ADR-0023: Multi-Minecraft-band publication model

**Status:** Accepted. The *model* (per-band artifacts, `mcdp-<band>` naming, `setSrcDirs` source sharing) is in force unchanged. Several *particulars* recorded below — the band table, the out-of-scope list, `mcdp-1.21`'s scope, the Forge-adapter status and the 26.x source-sharing premise — have since been corrected by [ADR-0029](0029-forge-1-17-shares-the-4-0-adapter.md), [ADR-0030](0030-mc-1-21-11-band.md), [ADR-0031](0031-mc-1-19-band.md) and [ADR-0032](0032-single-26x-band.md). The original text is kept as written; see **"Amendments (2026-09-20)"** at the end for the current picture. Adapters are no longer scaffolds on any loader: Fabric, NeoForge and Forge all ship working adapters (Forge completed by ADR-0027/0028; the one remaining Forge gap is `@EventBusSubscriber` auto-registration).

## Context

mcdp shipped v0.1.0–v0.1.2 supporting Minecraft 1.21.1 only, on Fabric + NeoForge. Real consumer mods (mc-fluid-physics) target many more MC versions — 1.15.2 through 1.21.1 plus the Mojang-renamed 26.1.x line — across Fabric, Forge, and NeoForge. Two questions:

1. **What versions are worth supporting?** mcdp's value proposition is highest where (a) the JPMS keyword-package check fires (NeoForge 1.20.5+, Mojang's calendar 26.x) and (b) cross-mod stdlib-version isolation matters. Pre-1.18 Forge has no JPMS issue and an established "just shadow your stdlib" workaround; the cost-to-value ratio there is poor.
2. **How are multiple bands published?** A single `de.lhns.mcdp:mcdp` artifact can't serve every MC version because (a) bytecode targets differ (Java 16/17/21 across bands), (b) loader SPIs differ (Forge `IModLanguageProvider` vs NeoForge `IModLanguageLoader`), (c) MC class names and FML internals diverge per version.

## Decision

### Supported Minecraft bands

| Band | MC versions | JDK | Loaders | Notes |
|---|---|---|---|---|
| `mcdp-1.17` | 1.17.x | 16 | Fabric + Forge | Lowest supported; pre-JPMS but shipped because dropping `core/` to Java 16 was free. |
| `mcdp-1.18` | 1.18.x | 17 | Fabric + Forge | Pre-JPMS Forge era. |
| `mcdp-1.20` | 1.20.1 | 17 | Fabric + Forge | Last pre-NeoForge band. |
| `mcdp-1.20.6` | 1.20.6 | 21 | Fabric + NeoForge | First JPMS-era NeoForge — primary value-prop band. |
| `mcdp-1.21` | 1.21.1 | 21 | Fabric + NeoForge | Currently shipped (was `mcdp` pre-rename). 1.21.1-**only**; confirmed by [ADR-0030](0030-mc-1-21-11-band.md). |
| `mcdp-26.1` | 26.1.x | 21+ | Fabric + NeoForge | Mojang's calendar-versioning era. **SUPERSEDED — this band does not exist.** Replaced by the single `mcdp-26` band, [ADR-0032](0032-single-26x-band.md); its directories are deleted. |

> **This table is out of date — see "Amendments (2026-09-20)" below for the current eight-band table.** Two bands were added after it was written (`mcdp-1.19`, ADR-0031; `mcdp-1.21.11`, ADR-0030) and `mcdp-26.1` was replaced by `mcdp-26` (ADR-0032).

**Explicitly out of scope:** 1.15.2, 1.16.x. Java 8 backports of `core/` (records, switch expressions, `StackWalker`) cost ~250 LOC of duplicate maintenance for niche demand. Mixin 0.7 (1.15.2) breaks the bridge codegen which assumes 0.8.x semantics. Anyone on those versions can shadow their stdlib into the jar.

> **Amended.** This list was never complete: 1.19 was absent from both the supported table and this exclusion list, with no recorded reason — [ADR-0031](0031-mc-1-19-band.md) establishes that none of the reasons above apply to it and **adds** `mcdp-1.19`. A second, deliberate gap has since opened that this section should name: **MC 1.21.2 – 1.21.9 are uncovered on purpose.** See the amendments below.

### Artifact naming

Consistent suffix scheme: every band publishes as `de.lhns.mcdp:mcdp-<band>`. The suffix is the MC band identifier (e.g. `1.17`, `1.20.6`, `26.1`). Pre-rename `mcdp:0.1.x` artifacts on Maven Central stay (immutable), unaffected; new releases publish under the suffixed names. Consumers update their pin once.

The unsuffixed `mcdp` coordinate is **not** reused for any band going forward — keeping it suffix-free for "latest" would rotate semantics over time and confuse consumers.

### Source structure

- `core/` and `deps-lib/`: target Java 16. Single source tree, all bands consume.
- `fabric/` (renamed `:fabric-1.21`): canonical Fabric adapter source. Other Fabric bands (`fabric-1.17/`, `fabric-1.18/`, `fabric-1.20/`, `fabric-1.20.6/`, `fabric-26.1/`) share this source via `sourceSets.main.java.setSrcDirs(listOf(rootProject.file("fabric/src/main/java")))` — *set*, not the additive `srcDirs(...)`: the band's own `src/main/java` must be replaced, not added to, or a stale tree would compile in silently. They differ only in `mcdpBand.javaRelease` (which the `mcdp.shaded-jar` convention feeds to `options.release`; `targetCompatibility` is not used anywhere in this build) and in the two `fabric.mod.json` floors. The Fabric `LanguageAdapter` + `PreLaunchEntrypoint` SPI surface is stable across fabric-loader 0.14+.
- `neoforge/` (renamed `:neoforge-1.21`): canonical NeoForge 21.x adapter source. **Not shared** with `:neoforge-1.20.6` — the NeoForge SPI diverged between 8.0.x (1.20.6) and 9.0.x (1.21). ~~It *is* shared with `:neoforge-26.1` via `setSrcDirs`, because the SPI has not in fact diverged between 21.x and 26.1; that band contributes only its own resources. Split it back out when a 26.x SPI line actually diverges.~~ **SUPERSEDED.** That premise expired: the SPI *did* diverge, at fancymodloader 9.0 → 10.0 (`IModFile.findResource` removed, `SecureJar` → `JarContents`, `FMLEnvironment.dist` → `getDist()`), and real 26.x ships loader 11/12 which carry those removals ([ADR-0030](0030-mc-1-21-11-band.md), postscript). There is a third NeoForge tree, `neoforge-1.21.11/` (the FML-10/11/12 port), and `neoforge-26/` shares **that** one — not `neoforge/`.
- `forge-1.18/`: the canonical Forge adapter (`McdpLanguageProvider` + `McdpModContainer`). `forge-1.17/` and `forge-1.20/` carry no Java source of their own — both point `srcDirs` at it and pin only their own forgespi/fmlcore coordinates. 1.17 can share it because Forge 1.17.1-37.1.2 actually runs **forgespi 4.0.x**, not the 3.2.x this ADR originally assumed (see [ADR-0029](0029-forge-1-17-shares-the-4-0-adapter.md)); the 7.x surface the 1.20 band uses is compatible for everything the adapter calls.
- `multi-<band>/` (Gradle path `:mcdp-<band>`): aggregator subproject. Bundles the band's adapters' shadowJars into one runtime artifact. Vanniktech maven-publish wired with `automaticRelease=true` per [ADR-0026](0026-automatic-release.md) (ADR-0020 originally specified `false`).

### Rejected alternatives

- **One artifact, multiple classifiers** (`mcdp:0.2.0:mc1.21`, `:mc1.20.6`). Sonatype Central Portal expects standard JAR coordinates; classifier publishing on the staging-bundle API is awkward. Suffix-as-artifactId is mechanically cleaner.
- **Architectury** for cross-loader source sharing. mcdp's adapters talk to fundamentally different SPIs (`LanguageAdapter` vs `IModLanguageLoader` vs `IModLanguageProvider`) — there's no common shape Architectury can abstract over. Architectury fits mods with shared game logic; mcdp is the adapter, not a mod.
- **Single Java-8 backport of core/** to support 1.15.2 + 1.16.x. ~250 LOC of duplicated maintenance for low value; rejected.

## Consequences

**Positive.**

- Each band is independently versioned and released. Bumping `mcdp-1.21` doesn't force a re-cut of `mcdp-1.20.6`.
- Source sharing via `setSrcDirs` (Fabric, where the SPI is stable) avoids duplicated maintenance for the common case — and, since the `fabric.mod.json` template landed, the metadata too.
- Forge SPI's stability across 3.2/4.0/7.x means once one Forge adapter is implemented properly, fanning out to the other bands is mostly mechanical.
- New bands are addable without disturbing existing ones. *(The worked example originally given here — "when Mojang ships 26.2 or later, adding `mcdp-26.2` is a copy-and-tweak of the existing `mcdp-26.1` scaffold" — was **not** followed. [ADR-0032](0032-single-26x-band.md) measured 26.1/26.2/26.3 as byte-identical on every SPI class mcdp touches and collapsed them into one `mcdp-26` band instead of three. The general claim stands; that instance of it was wrong.)*

**Negative.**

- **Forge 1.17 / 1.18 cannot consume the mcdp Gradle plugin.** *(SUPERSEDED — see "Errata" below. The Gradle-8-API half of this claim was never true, and the Java-21-bytecode half has been fixed. Forge 1.18 now consumes the plugin and is in CI.)* ForgeGradle 5.1 (the only FG line that supports MC ≤ 1.18) requires Gradle 7, which runs on Java ≤ 19. Our gradle-plugin compiles to Java 21 bytecode against Gradle 8 APIs. So Forge 1.17/1.18 modders can use the mcdp **runtime** (the `mcdp-1.18` jar consumed via Maven) but must generate their `META-INF/mcdepprovider.toml` outside of mcdp — manual TOML, or a script. Forge 1.20.x uses ForgeGradle 6 + Gradle 8, so the plugin works there. NeoForge bands (1.20.6+) also work because MDG is Gradle 8 native. Documented in the test-mod scaffolds.
- Eight subprojects per band (fabric-X, neoforge-X or forge-X, multi-X) × six bands = a lot of `build.gradle.kts` files. Mitigated by the `buildSrc` convention plugins (`mcdp.shaded-jar`, `mcdp.band-adapter`, `mcdp.band-aggregator`), which own the shadow/`bundle`/`apiElements` recipe and the publishing POM; a band file now carries only its repositories, its SPI pins, and an `mcdpBand { }` block of genuinely per-band values.
- NeoForge adapters can't share source today, so per-band feature work doubles. Future SPI re-stabilization could let us merge `:neoforge-1.20.6` and `:neoforge-1.21` source trees if their surfaces re-converge.
- Test-mod fixtures multiply: each band's CI smoke runs through Loom (Fabric), MDG (NeoForge), or ForgeGradle. Six bands × two-or-three loaders = up to 18 CI cells.

## Errata

### The Gradle-plugin-on-Forge-1.17/1.18 exclusion was half wrong

The "Consequences — negative" bullet above gave two reasons the mcdp `gradle-plugin` could not load under ForgeGradle 5.1 / Gradle 7. Re-checked against the source:

- **"compiles to Java 21 bytecode" — true, and it was the whole blocker.** `java-gradle-plugin` publishes Gradle Module Metadata carrying `org.gradle.jvm.version`. At target 21, a Gradle 7.6 daemon (JDK ≤ 19) has no matching variant and refuses the plugin before loading a single class.
- **"against Gradle 8 APIs" — false.** An exhaustive inventory of `org.gradle.*` imports across `gradle-plugin/src/main/java/**` yields 34 types, **all of which predate Gradle 8** (`Plugin`, `Project`, `Configuration`, `ResolvedArtifact`, `MavenArtifactRepository`, `RegularFileProperty`, `ListProperty`, `JavaPluginExtension`, `SourceSet`, `JavaExec`, `ProcessResources`, the task-annotation set, …). The behavioural surface was checked too, not just the types: no `ConfigurationRole` / `consumable()` / `resolvable()` / `dependencyScope()` factories, no `Problems` API, no `DependencyCollector`, no `MapProperty`, no `Provider.zip`, no build services, no `JvmTestSuite`. Configuration wiring still goes through the 7.x-era `maybeCreate` + `setCanBeResolved`/`setCanBeConsumed` path.

**Change made.** `:gradle-plugin` now compiles with `options.release = 17` instead of 21 (root `build.gradle.kts`); band adapters are unaffected, since `mcdp.shaded-jar` already overrides `options.release` per band from `mcdpBand.javaRelease`. No plugin source changed.

**Why 17 and not 16.** Two independent reasons. (1) `RunTaskClasspathPatch` uses `java.util.HexFormat`, added in Java 17, so 16 would not compile without a code change. (2) 16 would buy nothing: the plugin is a *build-time* artifact whose bytecode is loaded by the Gradle daemon JVM and never by a Minecraft JVM. The MC-1.17 Java-16 runtime floor that governs `core` and `deps-lib` simply does not reach it. 17 is also the floor ForgeGradle 5.1 users are already on — MC 1.18.2 requires Java 17 — and Gradle 7.6 runs on Java 8–19, so 17 is comfortably inside the window.

**Consequence.** `forge-example-1.18` joined the nightly `runserver-smoke-bands` matrix. `forge-example-1.17` did not, for an unrelated reason that this ADR already records: its adapter is a stub that throws. The plugin-path exclusion no longer applies to either band.

> **Correction.** The `forge-1.17` "stub that throws" reason was itself wrong — the band was never actually blocked. Forge 1.17.1-37.1.2 runs **forgespi 4.0.x**, not the 3.2.x this ADR assumed, so `forge-1.17` shares `forge-1.18`'s working adapter verbatim ([ADR-0029](0029-forge-1-17-shares-the-4-0-adapter.md)). No Forge band ships a stub today. `forge-example-1.17` is still out of the CI matrix, but now for a purely mechanical reason: it pins a Java 16 toolchain and GitHub runners ship no JDK 16.

## Operational findings (added during runtime verification)

These are SPI-port details that surfaced when actually booting the per-band test mods. Each is a per-band quirk worth recording so future bands can apply (or skip) the workaround knowingly.

### `ModContainer.contextExtension` is required pre-21.x

NeoForge fancymodloader 3.0.x (MC 1.20.6) and Forge fmlcore 4.0/7.x (MC 1.18, 1.20) both expose `protected Supplier<?> contextExtension` on `ModContainer`. FML's `ModLoadingContext.setActiveContainer(...)` calls `container.contextExtension.get()` unconditionally during lifecycle transitions. Vanilla `FMLModContainer` initializes it from a package-private `FMLJavaModLoadingContext`; subclasses outside that package can't reach the same constructor. `FMLModContainer` discovers it has a non-null value because of how it's wired in its own constructor — there is no public init helper.

NeoForge 4.0.x (MC 1.21) **dropped the field entirely**. So `McdpModContainer` worked unchanged on 1.21 but NPE'd on every earlier band the moment FML ran the lifecycle.

**Decision.** On bands that have the field, set `this.contextExtension = () -> this;` in the McdpModContainer constructor. The supplier value is never inspected for our use case — FML only uses it to populate `ModLoadingContext`, which entry classes read via `FMLJavaModLoadingContext.get()`. Mods that call that helper won't see a real context, but mods that don't (which is the common case for mcdp-loaded mods — they receive their `ModContainer` directly via the constructor bag, ADR-0017) boot fine. Applied in `forge-1.18/.../McdpModContainer.java` — which `:forge-1.20` compiles too, since it has no source tree of its own and points its `srcDirs` at forge-1.18's — and in `neoforge-1.20.6/.../McdpModContainer.java`. Skipped on the 1.21 canonical and on `neoforge-26.1` (same SPI line as 1.21, no field).

### `FMLModType` per-band: LANGPROVIDER vs LIBRARY

The aggregator jar's MANIFEST attribute that tells FML how to route the jar:

| Band aggregator | `FMLModType` | Why |
|---|---|---|
| `mcdp-1.17` | `LANGPROVIDER` | Was *(unset)* while forge-1.17 was a stub that would have thrown on service load. The band now shares the working 4.0 adapter (ADR-0029), so FML routes it like the other Forge-bundling bands. |
| `mcdp-1.18` | `LANGPROVIDER` | Forge 4.0.x recognizes the explicit hint; LIBRARY routing pre-dates the cpw module-layer reshuffle. |
| `mcdp-1.20` | `LANGPROVIDER` | Same as 1.18. |
| `mcdp-1.20.6` | `LIBRARY` | NeoForge 8.0.x uses `cpw.mods.securejarhandler`'s PLUGIN module layer — LIBRARY routes the jar there, where service-load picks up `IModLanguageLoader` automatically. |
| `mcdp-1.21` | `LIBRARY` | Same as 1.20.6 (NeoForge 4.0.x fancymodloader). |
| `mcdp-26.1` | `LIBRARY` | Same SPI line as 1.21. **Band renamed to `mcdp-26`** (ADR-0032); the `LIBRARY` choice carries over unchanged. |
| `mcdp-1.19` | `LANGPROVIDER` | *(Added by [ADR-0031](0031-mc-1-19-band.md).)* Pre-NeoForge Forge band, like 1.17/1.18/1.20. |
| `mcdp-1.21.11` | `LIBRARY` | *(Added by [ADR-0030](0030-mc-1-21-11-band.md).)* The `LIBRARY` jar type and the `META-INF/services/…IModLanguageLoader` discovery path are unchanged in FML 10. |

Getting the type wrong is silent: FML won't surface an error, the language provider just never gets discovered, and consumer mods fail with `Missing language mcdepprovider`. Recorded here so future band additions know to pick before runtime tells them.

### Per-band `fabric.mod.json` floors

ADR-0023 above states that Fabric bands share source via `setSrcDirs(listOf(rootProject.file("fabric/src/main/java")))`. The resources are shared the same way, but `fabric.mod.json` is a **template**: its `version` has to be filled in at build time, and the two `depends` floors are per-band:

```
1.17    fabricloader >=0.14, java >=16
1.18    fabricloader >=0.14, java >=17
1.20    fabricloader >=0.15, java >=17
1.20.6  fabricloader >=0.15, java >=21
1.21    fabricloader >=0.16.0, java >=21    (canonical fabric/)
26.1    fabricloader >=0.16, java >=21
```

Amended for the bands added since (values read off the `fabricLoaderVersion` / `javaRelease`
settings in each band's `build.gradle.kts`):

```
1.19    fabricloader >=0.14, java >=17      (ADR-0031)
1.21.11 fabricloader >=0.19, java >=21      (ADR-0030)
26      fabricloader >=0.19, java >=21      (ADR-0032; replaces the 26.1 row above)
```

A band that hard-pins the 1.21 floors fails at mod resolution on every older band: "Replace mod 'Fabric Loader' (fabricloader) 0.15.11 with version 0.16.0 or later". The original fix was one copy of the file per band — six 29-line files whose only differences were those two lines.

**Decision.** One template in `fabric/src/main/resources/fabric.mod.json`, expanded by `processResources` in the `mcdp.band-adapter` convention plugin from `mcdpBand.fabricLoaderVersion` (the only new per-band knob; `depends.java` reuses `mcdpBand.javaRelease`, which is the same number by construction) and `project.version`.

The `version` field is the reason this is wired at all rather than left as six literals: it has to be the real project version. Shipping a literal `${version}` — which every band's jar did before this — means Fabric's loader cannot parse it as semver, falls back to a `StringVersion`, and no consumer's `depends: { mcdepprovider: ">=0.1" }` can ever be satisfied. That shipped to Maven Central snapshots before it was caught.

Two mechanical notes for anyone touching this: `expand()` runs Groovy's `SimpleTemplateEngine` over the file, which fails on *any* stray `$` in the resource, so it is scoped with `filesMatching("fabric.mod.json")` — the only other resources in these source sets are `META-INF/services/` entries. And the property map is a `Provider`, absent when `fabricLoaderVersion` is unset, so the Forge and NeoForge adapters (which have no `fabric.mod.json`) go through the same convention plugin untouched.

### MDG ↔ NeoForge version pairing

`net.neoforged:neoforge:20.6.119` (the originally-pinned 1.20.6 NeoForge) does not advertise the `neoforge-dependencies` capability that ModDevGradle 2.x looks for at variant resolution. Result: `Could not resolve net.neoforged:neoforge:20.6.119 — Unable to find a variant providing the requested capability 'net.neoforged:neoforge-dependencies'`. The capability landed in NeoForge 20.6.~135+; pinning `20.6.139` + MDG `2.0.141` resolves it. The lesson is more general: NeoForge patch numbers are not interchangeable when MDG's variant schema bumps. Pin MDG and NeoForge together, not independently.

### CI coverage for these findings

`mc-smoke.yml` adds a `runserver-smoke-bands` job (nightly only) that boots `fabric-example-1.17`, `1.18`, `1.20`, `1.20.6`, `neoforge-example-1.20.6`, and `forge-example-1.20`, asserting the `[mcdp-smoke] mod=… boot ok` marker. The 1.21 cell stays in the existing `runserver-smoke` (push + nightly) for fast PR signal. 26.1 and Forge 1.17/1.18 are excluded for the reasons above.

> **Updated.** `forge-example-1.18` is no longer excluded — it joined the matrix once `:gradle-plugin` dropped to Java 17 bytecode (errata above), giving seven cells. `forge-example-1.17` is still out (JDK 16 toolchain, no JDK 16 on GitHub runners), and the 26.x cells are still out, but for a **toolchain** reason, not a Mojang-artifact one — see the amendments below. The bands added in ADR-0030/0031/0032 have no cells yet. The workflow's own "Excluded from coverage" comment block is the authoritative list.

## Amendments (2026-09-20)

Four later ADRs each corrected one particular of this one. Rather than edit the reasoning above,
this section states the current position in one place; the reasoning for each correction lives in
the ADR named against it. Where this section conflicts with the original text, **it wins**.

### Current supported bands

Read off `settings.gradle.kts` and each band's `build.gradle.kts`. **Eight bands**, not six. The
MC column names the point release each band actually pins and tests, which is narrower than the
original table's `1.17.x` / `1.18.x` wording — that narrowing is deliberate, not a conflict.

| Band | MC versions | `javaRelease` | Loaders | Adapter source | Established / changed by |
|---|---|---|---|---|---|
| `mcdp-1.17` | 1.17.1 | 16 | Fabric + Forge | shares `fabric/`, shares `forge-1.18/` | 0023; Forge share by [0029](0029-forge-1-17-shares-the-4-0-adapter.md) |
| `mcdp-1.18` | 1.18.2 | 17 | Fabric + Forge | shares `fabric/`; `forge-1.18/` is canonical | 0023 |
| `mcdp-1.19` | 1.19.2 (forgespi 6.0.0 is uniform across 1.19–1.19.4) | 17 | Fabric + Forge | shares `fabric/`, shares `forge-1.18/` | [0031](0031-mc-1-19-band.md) |
| `mcdp-1.20` | **1.20.1** | 17 | Fabric + Forge | shares `fabric/`, shares `forge-1.18/` | 0023 |
| `mcdp-1.20.6` | 1.20.6 | 21 | Fabric + NeoForge | shares `fabric/`; `neoforge-1.20.6/` own tree (FML 8.0.x) | 0023 |
| `mcdp-1.21` | **1.21.1 only** | 21 | Fabric + NeoForge | shares `fabric/`; `neoforge/` is canonical (FML 4.0.x) | 0023, scope confirmed by [0030](0030-mc-1-21-11-band.md) |
| `mcdp-1.21.11` | 1.21.10 + 1.21.11 (the FML-10 band) | 21 | Fabric + NeoForge | shares `fabric/`; `neoforge-1.21.11/` own tree | [0030](0030-mc-1-21-11-band.md) |
| `mcdp-26` | 26.1, 26.2, 26.3, … | 21 (bytecode; game needs JDK 25) | Fabric + NeoForge | shares `fabric/`, shares `neoforge-1.21.11/` | [0032](0032-single-26x-band.md) |

`mcdp-26.1` **does not exist**. It was never published to Maven Central, so nothing was stranded;
its `fabric-26.1/`, `neoforge-26.1/`, `multi-26.1/` and `test-mods/*-example-26.1/` directories are
deleted.

There are now **three** NeoForge source trees (`neoforge/` for FML 4.0.x, `neoforge-1.20.6/` for
8.0.x, `neoforge-1.21.11/` for 10/11/12) and **one** Forge tree (`forge-1.18/`, shared by four
bands spanning forgespi 4.0.x → 6.0.x → 7.x).

### `mcdp-1.20` covers 1.20.1, not "≤ 1.20.4"

An earlier audit flagged a contradiction between the band table's `1.20.1` and prose elsewhere
saying the band covers "≤ 1.20.4". **The table is the truth: `mcdp-1.20` is 1.20.1.** The band pins
`net.minecraftforge:forge:1.20.1-47.4.20` / forgespi 7.x, `test-mods/forge-example-1.20` declares
`versionRange = "[1.20.1,1.21)"`, and the only cell in CI boots 1.20.1. "≤ 1.20.4" is a statement
about a *different* boundary — the end of the pre-NeoForge, `LANGPROVIDER`-routed Forge era — and
should not be read as band coverage. 1.20.2–1.20.4 are not compiled against, not booted, and not
claimed; they are likely to work (same forgespi 7.x line) but that is **unverified**.

### Deliberate gaps

- **MC 1.21.2 – 1.21.9: uncovered on purpose** — five loader lines (FML 5.0 … 9.0) for versions
  that are neither the line's entry point nor its tail. The 9.0 line (1.21.5–1.21.8) is the
  cheapest add if demand appears. ([ADR-0030](0030-mc-1-21-11-band.md).)
- **1.15.2, 1.16.x: still out**, for the original reasons (Java 8 backport of `core/`; Mixin 0.7).
- **1.19 is no longer a gap** — [ADR-0031](0031-mc-1-19-band.md) adds it. The record contains no
  reason it was ever excluded; ADR-0031 does not invent one.

### Adapter status

No band ships a stub. Fabric and NeoForge adapters were complete before this ADR's errata; the
**Forge** adapter reached parity via [ADR-0027](0027-forge-lifecycle-staging.md) (mod lifecycle
wired — before it, no `IModBusEvent` reached an mcdp-loaded mod) and
[ADR-0028](0028-forge-cross-mod-registration.md) (stdlib promotion, the lazy bridge populator and
download progress, all previously inert on Forge). **Remaining Forge gap:** `@EventBusSubscriber`
auto-registration, which NeoForge gets via `AutomaticEventSubscriber.inject`.

### Subproject / CI-cell arithmetic

The "six bands → a lot of `build.gradle.kts` files / up to 18 CI cells" consequences above scale
with the table: eight bands × three subprojects = 24 band subprojects, plus `core`, `deps-lib`,
`gradle-plugin`, `cli`. CI has **not** grown to match — the nightly `runserver-smoke-bands` matrix
is 7 cells × 2 OSes, and the bands added today have no cells (their test mods are unbuilt, and the
26.x ones are unbuildable on the current root toolchain). `mc-smoke.yml`'s "Excluded from coverage"
comment is the authoritative account of what is and is not covered.

## Cross-references

- ADR-0001 — per-mod URLClassLoaders (the underlying isolation model, version-agnostic)
- ADR-0002 — JPMS unnamed-modules (the problem mcdp solves; only fires on NeoForge 1.20.5+)
- ADR-0008 / ADR-0018 / ADR-0021 — bridge codegen (assumes Mixin 0.8.x; another reason 1.15.2 is out)
- ADR-0016 — unified `mcdp` runtime jar (now per-band: `mcdp-X`)
- ADR-0020 — Maven Central publishing (carries forward; one publish workflow handles all bands' artifacts)
- ADR-0022 — `dev_roots` source-set output contract (band-agnostic; works the same on every band)
- ADR-0027 / ADR-0028 — the Forge adapter's lifecycle staging and cross-mod registration; together they retire this ADR's "adapter implementations still in progress" status for Forge
- ADR-0029 — corrects this ADR's forgespi-3.2.x premise for `forge-1.17`
- ADR-0030 — corrects `mcdp-1.21`'s scope, adds `mcdp-1.21.11`, and expires the 21.x/26.x NeoForge source-sharing premise
- ADR-0031 — adds `mcdp-1.19`, the band this ADR's table skipped without a reason
- ADR-0032 — replaces `mcdp-26.1` with a single `mcdp-26` band

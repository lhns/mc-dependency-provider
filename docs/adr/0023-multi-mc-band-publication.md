# ADR-0023: Multi-Minecraft-band publication model

**Status:** Accepted (scaffold landed; adapter implementations per-band still in progress).

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
| `mcdp-1.21` | 1.21.1 | 21 | Fabric + NeoForge | Currently shipped (was `mcdp` pre-rename). |
| `mcdp-26.1` | 26.1.x | 21+ | Fabric + NeoForge | Mojang's calendar-versioning era. |

**Explicitly out of scope:** 1.15.2, 1.16.x. Java 8 backports of `core/` (records, switch expressions, `StackWalker`) cost ~250 LOC of duplicate maintenance for niche demand. Mixin 0.7 (1.15.2) breaks the bridge codegen which assumes 0.8.x semantics. Anyone on those versions can shadow their stdlib into the jar.

### Artifact naming

Consistent suffix scheme: every band publishes as `de.lhns.mcdp:mcdp-<band>`. The suffix is the MC band identifier (e.g. `1.17`, `1.20.6`, `26.1`). Pre-rename `mcdp:0.1.x` artifacts on Maven Central stay (immutable), unaffected; new releases publish under the suffixed names. Consumers update their pin once.

The unsuffixed `mcdp` coordinate is **not** reused for any band going forward — keeping it suffix-free for "latest" would rotate semantics over time and confuse consumers.

### Source structure

- `core/` and `deps-lib/`: target Java 16. Single source tree, all bands consume.
- `fabric/` (renamed `:fabric-1.21`): canonical Fabric adapter source. Other Fabric bands (`fabric-1.17/`, `fabric-1.18/`, `fabric-1.20/`, `fabric-1.20.6/`, `fabric-26.1/`) share this source via `sourceSets.main.java.setSrcDirs(listOf(rootProject.file("fabric/src/main/java")))` — *set*, not the additive `srcDirs(...)`: the band's own `src/main/java` must be replaced, not added to, or a stale tree would compile in silently. They differ only in `mcdpBand.javaRelease` (which the `mcdp.shaded-jar` convention feeds to `options.release`; `targetCompatibility` is not used anywhere in this build) and in the two `fabric.mod.json` floors. The Fabric `LanguageAdapter` + `PreLaunchEntrypoint` SPI surface is stable across fabric-loader 0.14+.
- `neoforge/` (renamed `:neoforge-1.21`): canonical NeoForge 21.x adapter source. **Not shared** with `:neoforge-1.20.6` — the NeoForge SPI diverged between 8.0.x (1.20.6) and 9.0.x (1.21). It *is* shared with `:neoforge-26.1` via `setSrcDirs`, because the SPI has not in fact diverged between 21.x and 26.1; that band contributes only its own resources. Split it back out when a 26.x SPI line actually diverges.
- `forge-1.17/`, `forge-1.18/`, `forge-1.20/`: each has its own minimal source tree (currently a stub `McdpLanguageProvider`). Forge `IModLanguageProvider`'s top-level shape is stable across forgespi 3.2 (1.17), 4.0 (1.18), 7.x (1.20), so once one band's adapter is implemented the others mostly clone it.
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
- New bands are addable without disturbing existing ones (e.g. when Mojang ships 26.2 or later, adding `mcdp-26.2` is a copy-and-tweak of the existing `mcdp-26.1` scaffold).

**Negative.**

- **Forge 1.17 / 1.18 cannot consume the mcdp Gradle plugin.** *(SUPERSEDED — see "Errata" below. The Gradle-8-API half of this claim was never true, and the Java-21-bytecode half has been fixed. Forge 1.18 now consumes the plugin and is in CI.)* ForgeGradle 5.1 (the only FG line that supports MC ≤ 1.18) requires Gradle 7, which runs on Java ≤ 19. Our gradle-plugin compiles to Java 21 bytecode against Gradle 8 APIs. So Forge 1.17/1.18 modders can use the mcdp **runtime** (the `mcdp-1.18` jar consumed via Maven) but must generate their `META-INF/mcdepprovider.toml` outside of mcdp — manual TOML, or a script. Forge 1.20.x uses ForgeGradle 6 + Gradle 8, so the plugin works there. NeoForge bands (1.20.6+) also work because MDG is Gradle 8 native. Documented in the test-mod scaffolds.
- Eight subprojects per band (fabric-X, neoforge-X or forge-X, multi-X) × six bands = a lot of `build.gradle.kts` files. Mitigated by the `buildSrc` convention plugins (`mcdp.shaded-jar`, `mcdp.band-adapter`, `mcdp.band-aggregator`), which own the shadow/`bundle`/`apiElements` recipe and the publishing POM; a band file now carries only its repositories, its SPI pins, and an `mcdpBand { }` block of genuinely per-band values.
- NeoForge adapters can't share source today, so per-band feature work doubles. Future SPI re-stabilization could let us merge `:neoforge-1.20.6` and `:neoforge-1.21` source trees if their surfaces re-converge.
- Test-mod fixtures multiply: each band's CI smoke runs through Loom (Fabric), MDG (NeoForge), or ForgeGradle. Six bands × two-or-three loaders = up to 18 CI cells.

## Errata

### The Gradle-plugin-on-Forge-1.17/1.18 exclusion was half wrong

The "Consequences — negative" bullet above gave two reasons the mcdp `gradle-plugin` could not load under ForgeGradle 5.1 / Gradle 7. Re-checked against the source:

- **"compiles to Java 21 bytecode" — true, and it was the whole blocker.** `java-gradle-plugin` publishes Gradle Module Metadata carrying `org.gradle.jvm.version`. At target 21, a Gradle 7.6 daemon (JDK ≤ 19) has no matching variant and refuses the plugin before loading a single class.
- **"against Gradle 8 APIs" — false.** An exhaustive inventory of `org.gradle.*` imports across `gradle-plugin/src/main/java/**` yields 34 types, all of which predate Gradle 8: `DefaultTask`, `GradleException`, `Plugin`, `Project`, `JavaVersion`, `Action`, `artifacts.{Configuration, ModuleVersionIdentifier, ResolvedArtifact}`, `artifacts.repositories.{ArtifactRepository, MavenArtifactRepository}`, `file.{ConfigurableFileCollection, DirectoryProperty, DuplicatesStrategy, FileCollection, ProjectLayout, RegularFile, RegularFileProperty}`, `logging.Logger`, `model.ObjectFactory`, `plugins.JavaPluginExtension`, `provider.{ListProperty, Property, Provider}`, `tasks.{Input, InputFile, InputFiles, JavaExec, Optional, OutputDirectory, OutputFile, PathSensitive, PathSensitivity, SourceSet, TaskAction, TaskProvider}`, `tasks.bundling.Jar`, `language.jvm.tasks.ProcessResources`. The behavioural surface was checked too, not just the types: no `ConfigurationRole` / `consumable()` / `resolvable()` / `dependencyScope()` factories, no `Problems` API, no `DependencyCollector` or `getDependencyFactory()`, no `MapProperty`, no `Provider.zip`, no build services, no `JvmTestSuite`. Configuration wiring still goes through the 7.x-era `maybeCreate` + `setCanBeResolved`/`setCanBeConsumed` path.

**Change made.** `:gradle-plugin` now compiles with `options.release = 17` instead of 21 (root `build.gradle.kts`); band adapters are unaffected, since `mcdp.shaded-jar` already overrides `options.release` per band from `mcdpBand.javaRelease`. No plugin source changed.

**Why 17 and not 16.** Two independent reasons. (1) `RunTaskClasspathPatch` uses `java.util.HexFormat`, added in Java 17, so 16 would not compile without a code change. (2) 16 would buy nothing: the plugin is a *build-time* artifact whose bytecode is loaded by the Gradle daemon JVM and never by a Minecraft JVM. The MC-1.17 Java-16 runtime floor that governs `core` and `deps-lib` simply does not reach it. 17 is also the floor ForgeGradle 5.1 users are already on — MC 1.18.2 requires Java 17 — and Gradle 7.6 runs on Java 8–19, so 17 is comfortably inside the window.

**Consequence.** `forge-example-1.18` joined the nightly `runserver-smoke-bands` matrix. `forge-example-1.17` did not, for an unrelated reason that this ADR already records: its adapter is a stub that throws. The plugin-path exclusion no longer applies to either band.

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
| `mcdp-1.17` | *(unset)* | forge-1.17 adapter is still a stub; setting LANGPROVIDER would make FML try to load a service that throws. |
| `mcdp-1.18` | `LANGPROVIDER` | Forge 4.0.x recognizes the explicit hint; LIBRARY routing pre-dates the cpw module-layer reshuffle. |
| `mcdp-1.20` | `LANGPROVIDER` | Same as 1.18. |
| `mcdp-1.20.6` | `LIBRARY` | NeoForge 8.0.x uses `cpw.mods.securejarhandler`'s PLUGIN module layer — LIBRARY routes the jar there, where service-load picks up `IModLanguageLoader` automatically. |
| `mcdp-1.21` | `LIBRARY` | Same as 1.20.6 (NeoForge 4.0.x fancymodloader). |
| `mcdp-26.1` | `LIBRARY` | Same SPI line as 1.21. |

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

A band that hard-pins the 1.21 floors fails at mod resolution on every older band: "Replace mod 'Fabric Loader' (fabricloader) 0.15.11 with version 0.16.0 or later". The original fix was one copy of the file per band — six 29-line files whose only differences were those two lines.

**Decision.** One template in `fabric/src/main/resources/fabric.mod.json`, expanded by `processResources` in the `mcdp.band-adapter` convention plugin from `mcdpBand.fabricLoaderVersion` (the only new per-band knob; `depends.java` reuses `mcdpBand.javaRelease`, which is the same number by construction) and `project.version`.

The `version` field is the reason this is wired at all rather than left as six literals: it has to be the real project version. Shipping a literal `${version}` — which every band's jar did before this — means Fabric's loader cannot parse it as semver, falls back to a `StringVersion`, and no consumer's `depends: { mcdepprovider: ">=0.1" }` can ever be satisfied. That shipped to Maven Central snapshots before it was caught.

Two mechanical notes for anyone touching this: `expand()` runs Groovy's `SimpleTemplateEngine` over the file, which fails on *any* stray `$` in the resource, so it is scoped with `filesMatching("fabric.mod.json")` — the only other resources in these source sets are `META-INF/services/` entries. And the property map is a `Provider`, absent when `fabricLoaderVersion` is unset, so the Forge and NeoForge adapters (which have no `fabric.mod.json`) go through the same convention plugin untouched.

### MDG ↔ NeoForge version pairing

`net.neoforged:neoforge:20.6.119` (the originally-pinned 1.20.6 NeoForge) does not advertise the `neoforge-dependencies` capability that ModDevGradle 2.x looks for at variant resolution. Result: `Could not resolve net.neoforged:neoforge:20.6.119 — Unable to find a variant providing the requested capability 'net.neoforged:neoforge-dependencies'`. The capability landed in NeoForge 20.6.~135+; pinning `20.6.139` + MDG `2.0.141` resolves it. The lesson is more general: NeoForge patch numbers are not interchangeable when MDG's variant schema bumps. Pin MDG and NeoForge together, not independently.

### CI coverage for these findings

`mc-smoke.yml` adds a `runserver-smoke-bands` job (nightly only) that boots `fabric-example-1.17`, `1.18`, `1.20`, `1.20.6`, `neoforge-example-1.20.6`, and `forge-example-1.20`, asserting the `[mcdp-smoke] mod=… boot ok` marker. The 1.21 cell stays in the existing `runserver-smoke` (push + nightly) for fast PR signal. 26.1 and Forge 1.17/1.18 are excluded for the reasons above.

## Cross-references

- ADR-0001 — per-mod URLClassLoaders (the underlying isolation model, version-agnostic)
- ADR-0002 — JPMS unnamed-modules (the problem mcdp solves; only fires on NeoForge 1.20.5+)
- ADR-0008 / ADR-0018 / ADR-0021 — bridge codegen (assumes Mixin 0.8.x; another reason 1.15.2 is out)
- ADR-0016 — unified `mcdp` runtime jar (now per-band: `mcdp-X`)
- ADR-0020 — Maven Central publishing (carries forward; one publish workflow handles all bands' artifacts)
- ADR-0022 — `dev_roots` source-set output contract (band-agnostic; works the same on every band)

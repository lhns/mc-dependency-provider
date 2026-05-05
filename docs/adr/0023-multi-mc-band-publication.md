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
- `fabric/` (renamed `:fabric-1.21`): canonical Fabric adapter source. Other Fabric bands (`fabric-1.17/`, `fabric-1.18/`, `fabric-1.20/`, `fabric-1.20.6/`, `fabric-26.1/`) share this source via `sourceSets.main.java.srcDirs(rootProject.file("fabric/src/main/java"))` and only differ in their `targetCompatibility`. The Fabric `LanguageAdapter` + `PreLaunchEntrypoint` SPI surface is stable across fabric-loader 0.14+.
- `neoforge/` (renamed `:neoforge-1.21`): canonical NeoForge 21.x adapter source. **Not shared** with `:neoforge-1.20.6` or `:neoforge-26.1` — the NeoForge SPI diverged between 8.0.x (1.20.6), 9.0.x (1.21), and presumably a future 10.x for the 26.x line. Each NeoForge band gets its own source tree when the adapter implementation lands.
- `forge-1.17/`, `forge-1.18/`, `forge-1.20/`: each has its own minimal source tree (currently a stub `McdpLanguageProvider`). Forge `IModLanguageProvider`'s top-level shape is stable across forgespi 3.2 (1.17), 4.0 (1.18), 7.x (1.20), so once one band's adapter is implemented the others mostly clone it.
- `multi-<band>/` (Gradle path `:mcdp-<band>`): aggregator subproject. Bundles the band's adapters' shadowJars into one runtime artifact. Vanniktech maven-publish wired with `automaticRelease=true` per ADR-0020.

### Rejected alternatives

- **One artifact, multiple classifiers** (`mcdp:0.2.0:mc1.21`, `:mc1.20.6`). Sonatype Central Portal expects standard JAR coordinates; classifier publishing on the staging-bundle API is awkward. Suffix-as-artifactId is mechanically cleaner.
- **Architectury** for cross-loader source sharing. mcdp's adapters talk to fundamentally different SPIs (`LanguageAdapter` vs `IModLanguageLoader` vs `IModLanguageProvider`) — there's no common shape Architectury can abstract over. Architectury fits mods with shared game logic; mcdp is the adapter, not a mod.
- **Single Java-8 backport of core/** to support 1.15.2 + 1.16.x. ~250 LOC of duplicated maintenance for low value; rejected.

## Consequences

**Positive.**

- Each band is independently versioned and released. Bumping `mcdp-1.21` doesn't force a re-cut of `mcdp-1.20.6`.
- Source sharing via `srcDirs` (Fabric, where the SPI is stable) avoids duplicated maintenance for the common case.
- Forge SPI's stability across 3.2/4.0/7.x means once one Forge adapter is implemented properly, fanning out to the other bands is mostly mechanical.
- New bands are addable without disturbing existing ones (e.g. when Mojang ships 26.2 or later, adding `mcdp-26.2` is a copy-and-tweak of the existing `mcdp-26.1` scaffold).

**Negative.**

- **Forge 1.17 / 1.18 cannot consume the mcdp Gradle plugin.** ForgeGradle 5.1 (the only FG line that supports MC ≤ 1.18) requires Gradle 7, which runs on Java ≤ 19. Our gradle-plugin compiles to Java 21 bytecode against Gradle 8 APIs. So Forge 1.17/1.18 modders can use the mcdp **runtime** (the `mcdp-1.18` jar consumed via Maven) but must generate their `META-INF/mcdepprovider.toml` outside of mcdp — manual TOML, or a script. Forge 1.20.x uses ForgeGradle 6 + Gradle 8, so the plugin works there. NeoForge bands (1.20.6+) also work because MDG is Gradle 8 native. Documented in the test-mod scaffolds.
- Eight subprojects per band (fabric-X, neoforge-X or forge-X, multi-X) × six bands = a lot of `build.gradle.kts` files. Maintenance overhead per refactor.
- NeoForge adapters can't share source today, so per-band feature work doubles. Future SPI re-stabilization could let us merge `:neoforge-1.20.6` and `:neoforge-1.21` source trees if their surfaces re-converge.
- Test-mod fixtures multiply: each band's CI smoke runs through Loom (Fabric), MDG (NeoForge), or ForgeGradle. Six bands × two-or-three loaders = up to 18 CI cells.

## Operational findings (added during runtime verification)

These are SPI-port details that surfaced when actually booting the per-band test mods. Each is a per-band quirk worth recording so future bands can apply (or skip) the workaround knowingly.

### `ModContainer.contextExtension` is required pre-21.x

NeoForge fancymodloader 3.0.x (MC 1.20.6) and Forge fmlcore 4.0/7.x (MC 1.18, 1.20) both expose `protected Supplier<?> contextExtension` on `ModContainer`. FML's `ModLoadingContext.setActiveContainer(...)` calls `container.contextExtension.get()` unconditionally during lifecycle transitions. Vanilla `FMLModContainer` initializes it from a package-private `FMLJavaModLoadingContext`; subclasses outside that package can't reach the same constructor. `FMLModContainer` discovers it has a non-null value because of how it's wired in its own constructor — there is no public init helper.

NeoForge 4.0.x (MC 1.21) **dropped the field entirely**. So `McdpModContainer` worked unchanged on 1.21 but NPE'd on every earlier band the moment FML ran the lifecycle.

**Decision.** On bands that have the field, set `this.contextExtension = () -> this;` in the McdpModContainer constructor. The supplier value is never inspected for our use case — FML only uses it to populate `ModLoadingContext`, which entry classes read via `FMLJavaModLoadingContext.get()`. Mods that call that helper won't see a real context, but mods that don't (which is the common case for mcdp-loaded mods — they receive their `ModContainer` directly via the constructor bag, ADR-0017) boot fine. Applied in `forge-1.18/.../McdpModContainer.java`, `forge-1.20/.../McdpModContainer.java`, `neoforge-1.20.6/.../McdpModContainer.java`. Skipped on the 1.21 canonical and on `neoforge-26.1` (same SPI line as 1.21, no field).

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

### Per-band `fabric.mod.json` overrides

ADR-0023 above states "Fabric bands share source via `srcDirs(rootProject.file("fabric/src/main/java"))`". That share covers Java source only. Each Fabric band keeps its **own** `src/main/resources/fabric.mod.json` because the `depends.fabricloader` and `depends.java` floors diverge per band:

```
1.17    fabricloader >=0.14, java >=16
1.18    fabricloader >=0.14, java >=17
1.20    fabricloader >=0.15, java >=17
1.20.6  fabricloader >=0.15, java >=21
1.21    fabricloader >=0.16, java >=21    (canonical fabric/, unchanged)
26.1    fabricloader >=0.16, java >=21
```

The shared `fabric/src/main/resources/fabric.mod.json` was originally consumed by every band via `resources.setSrcDirs(listOf(rootProject.file(...)))`. Its hard-pinned `fabricloader >=0.16.0` / `java >=21` made every pre-1.20.6 band fail at mod resolution: "Replace mod 'Fabric Loader' (fabricloader) 0.15.11 with version 0.16.0 or later". Per-band overrides fix it cleanly. This is the only file that's per-band rather than shared inside the Fabric source-set.

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

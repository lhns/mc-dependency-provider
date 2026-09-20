# ADR-0032: One `mcdp-26` band for Mojang's whole calendar-versioning line

**Status:** Accepted. Supersedes the `mcdp-26.1` row of [ADR-0023](0023-multi-mc-band-publication.md).

## Context

[ADR-0023](0023-multi-mc-band-publication.md) introduced `mcdp-26.1`, described in its band
table as "Mojang's calendar-versioning era" but scoped in practice to 26.1.x — and it
predicted its own successor: *"when Mojang ships 26.2 or later, adding `mcdp-26.2` is a
copy-and-tweak of the existing `mcdp-26.1` scaffold."*

Mojang has since shipped **26.2** (2026-06-16) and **26.3** (2026-09-15). Taking that
prediction literally would give three aggregators over two adapters that each `setSrcDirs` at
the *same* trees, differing in nothing but a string. This repo has already spent three rounds
collapsing bands that compiled identical source (ADR-0029 folded `forge-1.17` onto the 4.0
adapter; `forge-1.20` and `neoforge-26.1` never had source trees of their own).

ADR-0023 is explicit about *why* bands exist: "(a) bytecode targets differ, (b) loader SPIs
differ, (c) MC class names and FML internals diverge per version." A band is justified when
one of those three actually moves — so the question is empirical, and it was answered by
inspecting published artifacts rather than release notes.

### What was measured

Upstream jars from `maven.fabricmc.net` and `maven.neoforged.net`, compared with `unzip` +
`javap` + `sha1sum`.

**Fabric.** The entire surface `fabric/src/main/java` touches — `LanguageAdapter`,
`PreLaunchEntrypoint`, `ModContainer` — is **byte-identical** (same SHA-1, class-file major 52)
between fabric-loader **0.16.9** (the 1.21-era pin) and **0.19.5** (current with 26.3).
ADR-0023's claim that this SPI "is stable across fabric-loader 0.14+" holds through 0.19.

**NeoForge.** The fancymodloader pairing across the calendar line:

| NeoForge | fancymodloader | class-file major |
|---|---|---|
| 26.1.2.109 (stable) | 11.0.15 | 69 (Java 25) |
| 26.2.0.88 (stable) | 11.0.16 | 69 |
| 26.3.0.7-beta | 12.0.0 | 69 |
| 21.1.x (the `neoforge/` band) | 4.0.4x | 65 (Java 21) |

`IModLanguageLoader`, `ModContainer`, `IModInfo` and `ModFileScanData` are **byte-identical
(same SHA-1)** across 11.0.15, 11.0.16 and 12.0.0 — i.e. across 26.1, 26.2 and 26.3 — and
API-identical to the 4.0.4x line on those four classes. `ModContainer` still has no
`contextExtension` field, so the ADR-0023 pre-21.x workaround stays off, as on 1.21. The
**only** thing that moved from 4.0.4x to 11.x/12.x is the class-file major version, 65 → 69,
because NeoForge recompiled on JDK 25 — a *toolchain* fact, not an SPI fact.

**Bytecode target.** MC 26.1, 26.2 and 26.3 all declare `javaVersion.majorVersion = 25` in
Mojang's `version_manifest_v2.json`. One Java floor for the whole line.

**MC internals.** mcdp's adapters reference zero Minecraft classes, so criterion (c) cannot
fire for this project on any band.

So across 26.1 → 26.3 nothing in criteria (a), (b) or (c) moved. A per-release band would
publish three byte-identical adapter jars under three coordinates.

## Decision

**One band, `mcdp-26`, covering the whole Mojang calendar-versioning line (26.1, 26.2, 26.3
and forward until something in criteria (a)/(b)/(c) actually diverges).**

`mcdp-26.1` is **superseded, not kept**. It was never published — Maven Central carries only
`de.lhns.mcdp:mcdp` (pre-rename), `de.lhns.mcdp:gradle-plugin` and the plugin marker — so
there is no consumer to strand and no immutable coordinate to respect. Renaming now costs
nothing; shipping `mcdp-26.1` and then discovering `mcdp-26.2` is the same jar would cost a
deprecation cycle. This is consistent with ADR-0023's naming rule: the suffix is "the MC band
identifier", and for the calendar line that identifier is `26`. It is *not* the rejected
unsuffixed-`mcdp`-means-latest pattern — `mcdp-26` has fixed semantics that never rotate.

| Band | MC versions | JDK (runtime) | Loaders | Notes |
|---|---|---|---|---|
| `mcdp-26` | 26.1, 26.2, 26.3, … | 25 | Fabric + NeoForge | One band for the calendar line. Replaces `mcdp-26.1`. |

### Structure

- `fabric-26/` — `setSrcDirs` at `fabric/src/main/{java,resources}`, like every other Fabric
  band. `fabricLoaderVersion = "0.19"`.
- `neoforge-26/` — `setSrcDirs` at **`neoforge-1.21.11/src/main/java`** (the FML-10 port), not
  `neoforge/src/main/java`; contributes only its `META-INF/services/…IModLanguageLoader`.
  `fmlModType = LIBRARY`, same FML routing as 1.21 and for the same reason.

  > **Correction to this ADR as first written.** The line above originally said
  > `neoforge/src/main/java`. The measurement in "What was measured" compares *SPI class
  > files*, which are indeed byte-identical, but real 26.x ships fancymodloader 11/12, which
  > carry the FML-10 removals that `neoforge/`'s 21.x source depends on
  > ([ADR-0030](0030-mc-1-21-11-band.md), postscript) and would link-error at runtime. The
  > build points at `neoforge-1.21.11/src/main/java` and compiles against loader **10.0.36**.
- `multi-26/` (Gradle path `:mcdp-26`) — aggregator bundling both.

### Two pins that look wrong and are not

**`javaRelease = 21`, on a band whose game needs Java 25.** `javaRelease` feeds
`options.release` (the *bytecode target*) and the `depends.java` floor in `fabric.mod.json`.
JVM bytecode is forward-compatible: release-21 classes load on a JVM 25. Setting it to 25
would require a JDK 25 toolchain for the whole root build (currently Gradle 8.11.1 / JDK 21)
and buy nothing at runtime. The `depends.java >= 21` floor is a floor — satisfied, not
violated, on a 26.x runtime. Raise it when the root toolchain moves.

**`neoforge-26` compiles against an older fancymodloader than it runs on.** The 26.x
fancymodloader jars are class-file major 69; javac on a JDK 21 toolchain cannot read them at
all (`class file has wrong version 69.0, should be 65.0`). The pin is **10.0.36**
(`libs.neoforge.fml.loader.mc12111`) — the oldest loader carrying the FML-10 API the shared
`neoforge-1.21.11/` port is written against, major 65, and API-compatible with 11.x/12.x for
every member that port calls (ADR-0030's postscript). *(Corrected: this paragraph originally
named 4.0.4x, which is the substitution the unverified `neoforge-26.1` scaffold made by
accident — an API generation too old, which is exactly why it would have link-errored. Here it
is measured and deliberate.)* Exit condition: re-pin to `loader:12.0.0` when the root build
gains JDK 25.

### Rejected alternatives

- **`mcdp-26.3` alongside `mcdp-26.1`.** Two coordinates, byte-identical jars — the
  duplication ADR-0029 and the `setSrcDirs` arrangements were introduced to remove.
- **`mcdp-26.3` superseding `mcdp-26.1`, keeping per-release naming.** Correct about the
  duplication, wrong about the name: it commits us to repeating this ADR at 26.4, and every
  consumer to a pin bump per quarterly Mojang release, for an artifact that does not change.
- **Keep the `mcdp-26.1` name and quietly widen its meaning.** Exactly the rotating-semantics
  confusion ADR-0023 rejected the unsuffixed `mcdp` coordinate to avoid.

### When to split the band again

Split `mcdp-26` when — and only when — one of ADR-0023's three criteria actually moves:
`IModLanguageLoader` / `ModContainer` / `LanguageAdapter` / `PreLaunchEntrypoint` changes
shape, `ModContainer.contextExtension` reappears, or Mojang moves off Java 25. The check is
mechanical and is the one performed for this ADR: diff the SHA-1s of those four NeoForge
classes and three Fabric classes between the newest pinned loader jar and the new one.

## Consequences

**Positive.**

- One band, one aggregator, one set of pins for the whole calendar line. 26.4 is a
  compatibility *check*, not a new subproject, and consumers pin `mcdp-26` once.
- The `neoforge-26` FML pin is now documented and bounded rather than inherited by accident.
- Two correctness fixes fell out of the measurement that would otherwise have been copied
  forward into every new 26.x band:
  - `fabric-example-26.1` pinned `mappings("net.fabricmc:yarn:26.1.2+build.1:v2")`. **No yarn
    build exists for any 26.x version** — `net.fabricmc:yarn` stops at `1.21.11+build.6` and
    `meta.fabricmc.net/v2/versions/yarn/26.3` returns `[]`. The calendar line publishes a
    single rolling `net.fabricmc:intermediary:0.0.0`. The 26.x test mods use
    `loom.officialMojangMappings()`.
  - `fabric-example-26.1` pinned `fabric-loom` at `1.9-SNAPSHOT`, a snapshot coordinate. The
    26.3 mod pins the release **1.18.2**.

**Negative.**

- **The band cannot be verified at runtime in CI, and stays excluded.** The blocker is
  entirely toolchain and is *not* about Minecraft: MC 26.3, fabric-loader 0.19.5, Fabric API
  `0.161.0+26.3` and Loom 1.18.2 are all published and stable. Loom refuses a MC version whose
  required Java exceeds the **Gradle daemon** JVM; MC 26.x requires 25; Gradle 8.11.1 cannot
  run on JDK 25. So the 26.x test mods need Gradle 9.6 + JDK 25 of their own and follow the
  `forge-example-1.17`/`-1.18` shape (own wrapper, no `includeBuild("../..")`, `mavenLocal()`
  first), and `runserver-smoke-bands` needs JDK 25 in its `setup-java` list before either cell
  can be enabled. Excluding a cell that cannot pass is better than a red cell.
- **The NeoForge smoke targets MC 26.2, not 26.3.** NeoForge's 26.3 line is beta-only
  (`26.3.0.0-beta` … `26.3.0.7-beta`); the newest stable is `26.2.0.88`. The band itself ships
  NeoForge support for the whole line, because the SPI is measured-identical across
  11.0.15/11.0.16/12.0.0, but the *runtime* smoke pins the stable loader so NeoForge's
  pre-release churn cannot show up as mcdp breakage.
- Loom 1.18.x declares `org.gradle.jvm.version = 25`, so it only ever runs on a JDK 25 daemon.
  Loom 1.17.21 is the last line declaring 21 — which buys nothing here, because Loom's
  *separate* MC-vs-daemon Java check still forces 25 for this band.
- Compiling the NeoForge adapter against an older fancymodloader than it runs on is a
  deliberate mismatch, safe only as long as the SPI classes stay byte-identical — now a
  documented check rather than an assumption.

## Cross-references

- ADR-0023 — the band model; its `mcdp-26.1` row, FMLModType table and `fabric.mod.json`
  floor table are all amended by this ADR
- ADR-0030 — the FML-10 port `neoforge-26/` shares, and the postscript establishing that 26.x
  ships loader 11/12
- ADR-0029 — the precedent for collapsing a band onto a shared adapter once the SPI is
  measured rather than assumed
- ADR-0026 — `automaticRelease=true`, inherited by `:mcdp-26` through `mcdp.band-aggregator`

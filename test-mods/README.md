# test-mods

Sixteen standalone example mods that exercise the full mcdp stack end to end.

Each directory is its **own** Gradle build (Loom, ModDevGradle or ForgeGradle) that
pulls mcdp in through composite-build `includeBuild("../..")` — they are not
subprojects of the root build, so `./gradlew :test-mods:…` does not reach them.
Build one with `cd test-mods/<mod> && ../../gradlew build`, or all of them with
`scripts/test-mods.sh <gradle-args>`.

Twelve of the sixteen run in CI. The other four are **deliberately excluded
scaffolds, not abandoned code** — the reasons are recorded below and in the
"Excluded from coverage" comment in `.github/workflows/mc-smoke.yml`.

## Coverage

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
| `fabric-example-1.20` | Fabric 1.20.1 | java | `runserver-smoke-bands` (nightly) |
| `fabric-example-1.20.6` | Fabric 1.20.6 | java | `runserver-smoke-bands` (nightly) |
| `neoforge-example-1.20.6` | NeoForge 20.6 / MC 1.20.6 | java | `runserver-smoke-bands` (nightly) |
| `forge-example-1.20` | Forge 1.20.1 | java | `runserver-smoke-bands` (nightly) |
| `fabric-example-26.1` | Fabric 26.1.2 | java | **excluded** — no MC artifacts yet (below) |
| `neoforge-example-26.1` | NeoForge 26.1 beta | java | **excluded** — no MC artifacts yet (below) |
| `forge-example-1.17` | Forge 1.17.1 | java | **excluded** — plugin path unusable (below) |
| `forge-example-1.18` | Forge 1.18.2 | java | **excluded** — plugin path unusable (below) |

## Why the four are excluded

### `fabric-example-26.1`, `neoforge-example-26.1`

MC 26.1.x is scaffold-only: Mojang has not shipped real artifacts for the band.
Loom fails with `26.1.2 requires Java 25 but Gradle is using 21`, and the NeoForge
26.1 line is beta-only. Both mods are complete, buildable-in-shape scaffolds
(build script, settings, entry class, loader manifest) kept so that adding the band
to CI is a matrix-row edit once real artifacts land. See ADR-0023 ("Supported
Minecraft bands", `mcdp-26.1`).

### `forge-example-1.17`, `forge-example-1.18`

ForgeGradle 5.1 is the only FG line that supports MC ≤ 1.18, and it forces Gradle 7
(Java ≤ 19), so these two mods run on their own Gradle 7.6 wrapper (see below).

**The mcdp plugin path works on these bands.** This section previously claimed it
did not, for two reasons; only one of them was ever real:

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

`forge-example-1.17` is **not**, but no longer because of the adapter. That band was
long believed to be stuck on forgespi 3.2.x, whose `loadMod` predates `ModuleLayer`
and whose `IModFileInfo` lacks `getFile()`. It isn't: Forge 1.17.1-37.1.2 requires
`forgespi 4.0.+`, so `forge-1.17` now shares the same working adapter as 1.18 and
1.20 (see [ADR-0029](../docs/adr/0029-forge-1-17-shares-the-4-0-adapter.md)). The one
thing still holding the row back is toolchain: it needs a JDK **16** in the
workflow's `setup-java` list, because
`forge-example-1.17` pins `toolchain { languageVersion = 16 }` and GitHub runners
ship no JDK 16. ADR-0023 also notes the `mcdp-1.17` aggregator leaves `FMLModType`
unset for the same stub-adapter reason.

### The Gradle 7.6 wrappers in `forge-example-1.17` / `forge-example-1.18`

These two mods are the only ones in `test-mods/` that commit their own wrapper
(`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) pinning **Gradle 7.6**, while the
repo root wrapper is 8.11.1. That is **load-bearing, not cruft**:

- ForgeGradle 5.1 rejects Gradle 8.x outright (`EnvironmentChecks.checkGradleRange`),
  so these builds must run on a Gradle 7 distribution.
- Consequently their `settings.gradle.kts` also omit `includeBuild("../..")` — the
  parent build needs Gradle 8 (Shadow 8.3.5), so it cannot be composite-included
  from a Gradle 7 build. Build them with `./gradlew` *from inside the mod
  directory*, after `../../gradlew :mcdp-1.17:publishToMavenLocal` (resp.
  `:mcdp-1.18:…`) has populated mavenLocal.

Every other test-mod intentionally has no wrapper and is driven by `../../gradlew`.

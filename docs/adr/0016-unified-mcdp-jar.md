# ADR-0016: Unified `mcdp` jar bundling Fabric and NeoForge adapters

**Status:** Accepted — shipped before v0.1.0.

## Context

Through ADR-0012 the project shipped two separate published artifacts:

- `de.lhns.mcdp:mcdp-fabric` — `:fabric` shadowJar (`:core` + `:deps-lib` + Fabric adapter classes + `fabric.mod.json`).
- `de.lhns.mcdp:mcdp-neoforge` — `:neoforge` shadowJar (`:core` + `:deps-lib` + NeoForge adapter classes + `META-INF/services/IModLanguageLoader` + `FMLModType=LIBRARY` manifest attr).

A consumer mod had to know which loader they were targeting and depend on the right artifact. With Architectury-style multi-loader mods on the rise, mod authors increasingly ship one jar that runs on both Fabric and NeoForge — so they need *one* mcdpprovider dependency, not a `if (loader == fabric) ... else ...` shape in their build.

The two adapter modules are also small (the entire Fabric adapter is two classes; NeoForge is two classes) and don't share much per-loader code. Shipping them as two artifacts costs disk and complexity for negligible benefit.

## Decision

Publish a single artifact, **`de.lhns.mcdp:mcdp`**, that bundles both adapters and runs unmodified on either loader. `:fabric` and `:neoforge` remain as Gradle subprojects but stop publishing — they exist only to feed `:mcdp`'s shadowJar and to keep their per-loader jars buildable for inspection/debugging.

The mechanics rely on three properties of the JVM and the loaders:

1. **Loaders ignore each other's metadata.** Fabric reads `fabric.mod.json` at the jar root; everything else in the jar is invisible to it. NeoForge / FML 4.0.x reads `META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader` (combined with the `FMLModType=LIBRARY` manifest attribute that routes the jar to the plugin module layer); it does not scan `fabric.mod.json`. Both metadata items can coexist in one jar without either loader getting confused.

2. **Classloaders are lazy.** `de.lhns.mcdp.fabric.McdpPreLaunch` references `net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint`. That class doesn't exist on a NeoForge runtime. But the JVM only verifies/links a class when it first gets referenced. On NeoForge, no live code path reaches `McdpPreLaunch` (FML never reads `fabric.mod.json`, so it never instantiates the entrypoint), so the missing `net.fabricmc.*` symbol never causes a `NoClassDefFoundError`. The class file just sits in the jar as bytes. Symmetric for `McdpLanguageLoader` on Fabric.

3. **`:core` and `:deps-lib` have zero compile-time imports of either platform's API.** Confirmed by exploration audit before adopting this ADR. Both modules are pure Java/JVM with no `net.fabricmc.*` or `net.neoforged.*` types. `ModClassLoader` has the strings `"net.neoforged."` and `"net.fabricmc."` in its `PLATFORM_PREFIXES` array for parent-first delegation, but these are runtime class-name filters, not imports.

The cross-loader pattern is the same one Architectury and the multi-loader template use. We don't depend on Architectury itself — we sit at the loader-provider layer, *underneath* whatever multi-loader API consumer mods choose to use.

### Build-side mechanics

- The Gradle subproject is named **`:mcdp`** (matching the published artifactId). Composite-build auto-substitution maps a consumer's `de.lhns.mcdp:mcdp:VERSION` request to this project automatically — no explicit `dependencySubstitution` block is needed in test-mod settings.
- `:mcdp:shadowJar` consumes `:fabric:runtimeElements` and `:neoforge:runtimeElements` (which both deliver shadowJars carrying `:core` + `:deps-lib`) via a custom `bundle` configuration. Shadow's `mergeServiceFiles()` correctly concatenates `META-INF/services/` entries; `EXCLUDE` drops the second copy of `:core`/`:deps-lib` classes coming through both inputs.
- The unified jar's `META-INF/MANIFEST.MF` carries `FMLModType: LIBRARY` and `Automatic-Module-Name: mcdepprovider`, both required by NeoForge and harmless to Fabric.
- `:fabric` and `:neoforge` no longer apply the `maven-publish` plugin and have no `publishing { ... }` block. Their shadowJars still build for diagnostic use.

### Composite-build consumer wiring

Test-mods (and any downstream Gradle consumer using `includeBuild`) need *both*:

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../..")   // contributes plugins (the `de.lhns.mcdp` Gradle plugin)
    repositories { ... }
}

includeBuild("../..")       // contributes modules (auto-substitutes mcdp → :mcdp)
```

The `pluginManagement.includeBuild` form does not contribute modules; the top-level `includeBuild` does not contribute plugins. Both are required. Same path is fine; Gradle merges them.

## Consequences

**Positive:**
- One artifact for consumers to depend on.
- Cross-loader mods can depend on a single mcdpprovider jar, no per-loader branching.
- Shipping/publishing surface drops from two artifacts to one.

**Negative:**
- Slightly larger jar than either platform-specific jar (extra adapter classes for the unused platform). For mcdpprovider this is ~5 KB of bytecode, negligible.
- Cross-import drift risk: if someone later writes code in `:core` or `:deps-lib` that imports `net.fabricmc.*` or `net.neoforged.*`, the unified jar will fail at runtime on the *other* loader (NoClassDefFoundError when that code path runs). Today both modules are clean. A build-time guard scanning compiled class files for forbidden import prefixes is tracked as a follow-up.

**Neutral:**
- `:fabric` and `:neoforge` Gradle subprojects survive as build-internal modules. Anyone debugging "is this a Fabric-side or NeoForge-side bug?" can still run `./gradlew :fabric:shadowJar` and inspect the platform-only jar.

## Alternatives rejected

- **Keep two artifacts, add a third unified one.** More publishing surface; no clear consumer ask. Rejected for simplicity.
- **Use Architectury Loom + Stonecutter.** Solves a different problem (multi-loader mod *API*, with build-time source preprocessing). We don't ship API for mods to consume — we sit beneath the API surface — so the heavy machinery isn't useful here.
- **Ship a thin `mcdp` umbrella that depends on `mcdp-fabric` *or* `mcdp-neoforge`.** Doesn't work: a consumer in a multi-loader mod would still need to express "pick one based on the platform," which is exactly what they wanted to avoid.

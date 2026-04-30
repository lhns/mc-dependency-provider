# ADR-0022: `dev_roots` is the plugin's contract for the per-mod ModClassLoader's URL list

**Status:** Accepted — refines ADR-0007 (dev-mode parity); pins manifest-side wiring used by ADR-0018 / ADR-0021 bridge codegen.

## Context

In dev runs, mod classes don't live inside a jar — they live across several source-set output directories the build tool produces (`build/classes/java/main`, `build/classes/scala/main`, `build/resources/main`, `build/mcdp-bridges/classes`, …). The per-mod `ModClassLoader` needs every one of those on its URL list, child-first, so that:

1. The mod's own classes (and now bridge impls in `<bridgePackage>_impl`, see ADR-0021 errata) are defined by the per-mod loader, not by the platform's parent loader (Knot on Fabric, `cpw.mods.cl.ModuleClassLoader` on NeoForge).
2. References from those classes to mod-private types (Scala/Kotlin stdlib, the mod's own runtime types) resolve via the per-mod loader's library URLs, not via the parent loader's URL list — which doesn't have those libs and would fail with `NoClassDefFoundError` or, worse, define a *second* copy of a mod class on the wrong loader.

Two prior shapes have been tried:

1. **Filesystem walk-up at runtime.** Each adapter (`McdpPreLaunch` on Fabric, `McdpLanguageLoader` on NeoForge) inspected `mod.getRootPaths()` / `IModFile.getFilePath()`, walked up to find a `build/` ancestor, then guessed sibling dirs (`build/classes/{java,scala,kotlin}/main`, `build/resources/main`, `build/mcdp-bridges/classes`). This couples mcdp's runtime to Gradle's default `buildDirectory` name and to the literal bridge output path. Two adapters had to be kept in sync; the asymmetry between them produced a Fabric-only crash that took two commits to find (`6641823` for the missing bridge dir on Fabric; `144db48` to lift the heuristic into the manifest).
2. **Manifest-driven `dev_roots` + adapter-side fallback.** `144db48` made the gradle plugin emit `dev_roots` from `main.getOutput().getFiles()` and removed the runtime walk-up. The plugin's emission relied implicitly on `main.getOutput().dir(builtBy=bridgeTask, …)` to register the bridge dir on the source-set output, then inheriting it through `getFiles()`. That implicit chain breaks under Loom/MDG re-rooting: by the time the manifest provider is queried, the source-set view may no longer carry the bridge dir. Snapshot `20260430.172737-2` shipped this hole and crashed both Fabric and NeoForge in the fluidphysics consumer (different surface symptoms, same root cause: bridge impl defined by the parent loader because the per-mod URL list missed `build/mcdp-bridges/classes`).
3. **Re-introducing a runtime safety net** (briefly shipped in `77545ce`). A `DevRootSafetyNet` helper walked each modPath looking for a sibling `build/` and appended the bridge dir if present. This worked on the consumer but reintroduced the very runtime filesystem-shape inference (1) was meant to retire.

## Decision

The contract is on the plugin side: `dev_roots` in the emitted manifest is the authoritative list. The runtime adapter trusts it verbatim (after filtering with `Files::isDirectory` so paths that don't exist on the consumer's machine — e.g. absolute build-machine paths captured into a published jar — are silently dropped).

### Plugin obligations

`gradle-plugin/src/main/java/de/lhns/mcdp/gradle/McdpProviderPlugin.java`:

1. Register `generateMcdpBridges` (`BridgeCodegenTask`) **before** `generateMcdpManifest` (`GenerateMcdpManifestTask`), so the manifest task can reference the bridge task as a `TaskProvider`.
2. The manifest task `dependsOn(bridgeTask)` — explicit, not implicit through `main.output.dir(builtBy=…)`.
3. The manifest task's `dev_roots` provider emits the union of:
    - `main.getOutput().getFiles()` — every source-set output the build tool already knows about,
    - `bridgeTask.flatMap(BridgeCodegenTask::getOutputClassesDir)` — the bridge codegen output, sourced from the bridge task itself so the path lives in exactly one place.

   De-duplication via `LinkedHashSet`: `main.output.getFiles()` *may* contain the bridge dir if `main.output.dir(...)` registration is still visible at provider-evaluation time; explicit append doesn't harm idempotence.

The literal `"mcdp-bridges/classes"` lives **only** in `BridgeCodegenTask.getOutputClassesDir`'s default initializer. Nothing else hardcodes it.

### Runtime obligations

`fabric/.../McdpPreLaunch.java` and `neoforge/.../McdpLanguageLoader.java`:

1. Read `manifest.devRoots()`, map to `Path`, filter with `Files::isDirectory` (drops jar-root absolute paths that don't exist on the consumer machine).
2. If the filtered list is non-empty, that's `modPaths`. Otherwise fall back to `mod.getRootPaths()` (Fabric) or `List.of(modFile)` (NeoForge).
3. No filesystem inference, no walk-up, no string literals for build-tool-specific layouts.

The runtime never tries to *fix* a malformed manifest. If a consumer's manifest is missing the bridge dir, the symptom is loud and the fix is to rebuild against a current plugin.

### Production-jar consumers

In a published mod jar, `dev_roots` either is absent (manifest written by an old plugin that didn't emit the field) or contains absolute paths from the build machine that don't exist on the consumer's filesystem. Both cases are handled by the runtime filter (`Files::isDirectory`): non-existent paths drop out, the list goes empty, the adapter falls back to `mod.getRootPaths()` / `modFile`, and the per-mod loader resolves classes from the jar root as it always did.

## Consequences

**Positive.**

- One source of truth for the bridge dir path: `BridgeCodegenTask.getOutputClassesDir`.
- One source of truth for what's on the per-mod ModClassLoader's URL list in dev: the manifest's `dev_roots`. Adapters don't reason about Gradle layouts.
- `dev_roots` field works for any future codegen output the plugin adds: as long as the plugin appends to the provider, runtime adapters need no change.
- Cross-build-tool readiness: an sbt or Maven plugin (ADR-0011) can populate `dev_roots` with its own conventions; the runtime contract is unchanged.

**Negative.**

- A consumer with an old (`dev_roots`-less) manifest plus a new runtime adapter sees the original bug. Mitigation: snapshots are ephemeral and the next CI publish replaces them; for releases, the post-v0.1 publish is the first to ship this contract, so there's no released-jar regression to migrate around.
- Production jars carry absolute paths in `dev_roots` that are dead on the consumer's machine. Harmless (filtered out), but it is data the consumer doesn't need. Acceptable cost — the alternative (omitting the field in jar builds) would require the gradle plugin to know whether it's emitting a dev artifact, which Loom/MDG don't telegraph cleanly.

## Alternatives considered

### Runtime walk-up to find `build/<lang>/main` and `build/mcdp-bridges/classes`

Tried first. Breaks when `buildDirectory` is renamed; couples runtime to Gradle's default layout; needs separate logic per platform adapter (because `getFilePath()` shapes differ). Rejected for the same reason ADR-0007 favors the build-time manifest pipeline over runtime guessing.

### Implicit registration via `main.output.dir(builtBy=bridgeTask, dir=…)`

Tried in `144db48`. Loom/MDG re-roots the source set after our `apply` block; by the time the manifest provider is queried, `main.output.getFiles()` no longer carries the bridge dir. Rejected: relying on Gradle ordering invariants we don't control.

### Runtime safety net (`DevRootSafetyNet`)

Briefly shipped in `77545ce`. Walks each modPath up to find `build/`, appends `mcdp-bridges/classes` if reachable. It worked on the fluidphysics consumer, but reintroduced exactly the runtime filesystem-shape inference the manifest contract is meant to retire. Two sources of truth at runtime for the same fact. Removed in this ADR.

## Cross-references

- **ADR-0007** — dev-mode runs through the production manifest pipeline. ADR-0022 sharpens that pipeline's plugin-side contract.
- **ADR-0018** / **ADR-0021** — mixin bridge codegen + generalized seeding. The bridge impl package is what falls off the URL list when `dev_roots` is wrong; ADR-0022 ensures it doesn't.
- **`gradle-plugin/src/main/java/de/lhns/mcdp/gradle/McdpProviderPlugin.java`** — the registration code that enforces the contract.
- **`gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/BridgeCodegenTask.java`** — `getOutputClassesDir` is the single source of truth for the bridge classes dir path.
- **`deps-lib/src/main/java/de/lhns/mcdp/deps/Manifest.java`** / **`ManifestIo.java`** — `dev_roots` field shape.
- **`fabric/src/main/java/de/lhns/mcdp/fabric/McdpPreLaunch.java`** / **`neoforge/src/main/java/de/lhns/mcdp/neoforge/McdpLanguageLoader.java`** — the consumers of `dev_roots`.

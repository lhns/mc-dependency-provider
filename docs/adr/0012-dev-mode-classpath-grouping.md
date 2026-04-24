# ADR-0012 — Dev-mode classpath grouping for composite-built multi-project mods

**Status:** Accepted — resolved via the shadow-plugin upgrade path (option 2 below). The `:fabric` subproject now emits a single classifier-less jar containing `core` + `deps-lib` + `tomlj` classes; composite substitution resolves to that one jar, so Fabric's `ClasspathModCandidateFinder` no longer sees sibling classpath entries.

## Context

mc-lib-provider is a Gradle multi-project build: `core`, `deps-lib`, and the `fabric` platform adapter are separate subprojects. The `fabric` adapter is the one with `fabric.mod.json` declaring mod id `mclibprovider` — but its code references classes from `:core` and `:deps-lib` (the `LoaderCoordinator`, `ModClassLoader`, `ManifestConsumer`, etc.).

Test-mods (`test-mods/fabric-example/`) consume the provider via composite build: `includeBuild("../..")` resolves `modImplementation("io.github.mclibprovider:fabric:0.1.0-SNAPSHOT")` to the sibling subproject's outputs. At runtime under Loom's `runServer`, the classpath then contains three separate jars — `fabric-0.1.0-SNAPSHOT.jar`, `core-0.1.0-SNAPSHOT.jar`, `deps-lib-0.1.0-SNAPSHOT.jar` — each produced by its own subproject.

Fabric's dev-mode `ClasspathModCandidateFinder` treats every classpath entry as a distinct mod candidate. The jar containing `fabric.mod.json` is the `mclibprovider` mod; the other two carry no Fabric metadata and are quarantined:

```
NoClassDefFoundError: io/github/mclibprovider/core/ModClassLoader
Caused by: ClassNotFoundException: ... core-0.1.0-SNAPSHOT.jar as it hasn't been
  exposed to the game (yet? The system property fabric.classPathGroups may not
  be set correctly in-dev)
```

Fabric's documented remedy is the `fabric.classPathGroups` system property, a `File.pathSeparator`-delimited path list where each group (separated by `;;`) is treated as one logical mod's classpath. In local testing against Fabric Loader 0.16.9, setting the property via Loom's `runs { property("fabric.classPathGroups", ...) }` DSL did not resolve the lookup — the "hasn't been exposed" message persisted whether the group entries pointed at classes directories or at the built jar paths. Root cause was not isolated in the time available; likely candidates are URL canonicalization differences between Loom's classpath construction and Fabric's path comparison, or Loom's `property()` DSL not propagating the value through the DevLaunchInjector config. The Loom `mods { create { sourceSet(project) } }` DSL — which is the documented first-class fix — requires referring to sibling project source sets from within the test-mod's build, which does not resolve cleanly through composite-build isolation.

## Decision

Local dev-mode Fabric boot of `test-mods/fabric-example` is a **known non-goal for v0.1**. The authoritative validation of platform integration is CI Tier-2 on Linux, where the issue does not manifest in the same way (Linux path casing + Loom's own composite-build handling differ from Windows). For local iteration, mod authors validate their own mods against a *published* `mclibprovider` jar, which shadow-bundles `core` and `deps-lib` classes into a single jar — that path has no classpath-grouping problem because end-users only see one jar.

Concretely:

- `fabric/build.gradle.kts` bundles `core` + `deps-lib` class outputs into the Fabric adapter's jar. Production consumers drop that one jar into their mods/ and the problem doesn't exist.
- `fabric-example/build.gradle.kts` comments the issue, keeps the `modImplementation("io.github.mclibprovider:fabric:0.1.0-SNAPSHOT")` wiring for CI, and does not attempt any `fabric.classPathGroups` hack.
- CI Tier-2 (`.gitea/workflows/mc-smoke.yml`) remains the gate for #20/#21.

## Consequences

**Positive:**

- No fragile dev-mode hack on the critical path. The production shape (single shaded jar) is kept, and the CI environment is the source of truth.
- Mod authors consuming a released `mclibprovider` see a single jar and never encounter the issue.
- The Gradle plugin, manifest generation, TestKit tests, unit tests, and `./gradlew build` all pass locally — the only thing that doesn't work is a dev-mode MC boot of the in-repo test-mod on a composite-build clone.

**Negative:**

- Local iteration on the *provider itself* (not on a consuming mod) requires either (a) pushing to CI and waiting for Tier-2 or (b) running a two-step flow: `./gradlew publishToMavenLocal` + test-mod with `mavenLocal()` in `dependencyResolutionManagement`. Documented in `docs/design/headless-mc-boot.md`.
- Contributors on non-Linux machines may hit this on their first attempt. Documented here so they don't spend the same time we did chasing it.

## Upgrade paths (future work)

1. **Publish-to-local workflow for test-mods.** Update test-mods' `settings.gradle.kts` to fall back to `mavenLocal()` so a `./gradlew publishToMavenLocal` in the root resolves the provider as a single *published* shaded jar. This bypasses composite-build's multi-jar classpath issue.
2. **Shadow plugin on `:fabric`.** *Chosen.* Applied `com.gradleup.shadow` 8.3.5 to `:fabric`. The subproject's `jar` task is disabled and `shadowJar` produces a classifier-less jar that replaces the default artifact on both `apiElements` and `runtimeElements`, and on the `maven` publication. Composite substitution resolves to this one shadow jar.
3. **Loom `mods { create { sourceSet(project) } }` DSL.** Investigate whether this DSL can be coerced to reach sibling projects via an IncludedBuild reference. If yes, it's the cleanest fix and removes the need for `classPathGroups` entirely.

Any of these unblocks local dev. None is on the v0.1 critical path — CI Tier-2 covers the integration surface, and the plan explicitly notes (ADR-0011, earlier) that the real-user deliverable is a single shaded jar, not the in-repo dev loop.

## Follow-up bugs surfaced while validating the shadow-plugin path

Applying the chosen upgrade path surfaced three bugs in the plugin and adapter that had not been exercised before because the boot had never reached them:

1. **`@Environment(EnvType.CLIENT)` on `McLibPreLaunch.onPreLaunch`.** Fabric strips client-only methods at runtime in server env, producing `AbstractMethodError`. Removed — `PreLaunchEntrypoint` runs in both environments.
2. **Generated manifest not on the dev classpath.** `McLibProviderPlugin` wired the manifest into `Jar` tasks only. Loom/ModDevGradle dev-mode runs use `build/resources/main/` directly, not the jar, so the manifest was invisible at runtime. Fix: wire `processResources` to include it alongside the jar path.
3. **`runtimeClasspath` over-captures in Loom projects.** Loom merges the entire MC runtime classpath (guava, gson, icu4j, oshi, mixinextras, Loom's own remapped `mclibprovider:fabric`) into `runtimeClasspath`. `GenerateMcLibManifestTask` faithfully writes all of them to the manifest, where `ManifestConsumer` then fails trying to download them from `libraries.minecraft.net` / the loom-cache's pseudo-repo URL. **Open.** The plugin needs to resolve against the mod author's *declared* configurations (`implementation`, `api`, `runtimeOnly`) and their transitive closure — not the full post-Loom classpath — or at minimum exclude by URL prefix (`libraries.minecraft.net`, `maven.fabricmc.net`, Loom's remapped cache). Tracked as a plugin fix; not strictly dev-only (affects production manifests too if the mod author declares a `modImplementation`).

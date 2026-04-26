# ADR-0014 — Opt-in `mcdepImplementation` Gradle bucket replaces `excludeGroup`

**Status:** Accepted — shipped in `8d07919`.

## Context

The original gradle-plugin had a `mclibprovider { excludeGroup("…") }` DSL where mod authors enumerated platform-provided Maven groups (Minecraft, NeoForge, Fabric, Mojang, the provider itself, occasionally `org.ow2.asm` and `org.spongepowered`). The plugin walked Gradle's resolved `runtimeClasspath` and emitted everything that wasn't excluded into `META-INF/<provider>.toml`.

This had two recurring problems:

1. **Mod authors had to rediscover the platform set.** Every test-mod's `build.gradle.kts` carried 4–6 `excludeGroup(...)` lines copied from another build. New patches to Loom or ModDevGradle that pulled in a new transitive group (mixinextras, ASM-renamed, oshi, …) would silently leak into the manifest until someone noticed and added another exclusion. The list was never actually finite.
2. **The `ListProperty.convention(...)` Gradle gotcha** (recorded in `docs/pitfalls.md`): defaults vanished as soon as a user appended their own. Required a workaround merge in the manifest task.

The architectural mistake was opt-out instead of opt-in. A mod has at most a handful of deps it actually wants served through mc-lib-provider; the rest of `runtimeClasspath` is platform stuff the mod doesn't ship. Listing the small set of inclusions is dramatically less work — and stable across platform churn — than listing the long tail of exclusions.

## Decision

Add a **declarable Gradle configuration** named `mcdepImplementation` (the dep-bucket convention from Loom's `modImplementation`). Mod authors place exactly the deps they want served through mc-lib-provider on this bucket; everything else stays where it lived (`implementation`, `modImplementation`, `compileOnly`, the platform's own configurations).

```kotlin
dependencies {
    modImplementation("net.fabricmc:fabric-loader:0.16.9")     // platform — not in manifest
    mcdepImplementation("org.typelevel:cats-core_3:2.13.0")    // mclib-served — in manifest
    mcdepImplementation("io.circe:circe-parser_3:0.14.10")     // mclib-served — in manifest
}
```

### Configuration topology

Three configurations registered by the plugin:

- **`mcdepImplementation`** — declarable bucket (`canBeResolved=false, canBeConsumed=false`). Users add deps here. Doesn't extend anything.
- **`implementation`** — extends from `mcdepImplementation` (configured by the plugin in `withPlugin("java")`). This makes mclib-served deps visible on the compile + runtime classpath in dev so sources compile against them and Loom/MDG see them as ordinary jars (`RunTaskClasspathPatch` then strips them at run-time per ADR-0007).
- **`mcdepManifest`** — resolvable view (`canBeResolved=true`) that extends *only* from `mcdepImplementation`. The manifest task and the dev-cache task both consume this. Never reads `runtimeClasspath`, so platform jars cannot leak into the manifest by accident.

### Build-time set-difference for transitives

A subtle concern raised during design: what if a transitive of `mcdepImplementation("X")` is also a transitive of some platform jar? The platform already has it — emitting it into the manifest would be redundant but harmless (the runtime would re-download something that's already on the parent CL).

A second resolvable configuration **`mcdepPlatformView`** extends from the rest of the project's classpath (`api`, `implementation`, `runtimeOnly`, `compileOnly`) and resolves it. The manifest task computes:

```
manifest = closure(mcdepManifest) − {a where (a.group, a.name) appears in mcdepPlatformView \ mcdepManifest}
```

Anything in the platform-side closure that's *not* also in the mclib-side closure is genuinely platform-provided and gets dropped. Plus a hard-coded subtraction of `de.lhns.mcdp:*` (the provider ships via the platform adapter jar, never via the manifest).

## Consequences

**Positive:**

- **Mod-author surface is shorter and stable.** No more `excludeGroup` lines. New platform transitives don't leak into the manifest because they're never in the resolvable view we walk.
- **No more `ListProperty.convention(...)` gotcha** — the merge-default workaround is gone with the DSL it served.
- **Symmetric across Loom + ModDevGradle.** Loom's `modImplementation` and MDG's runtime-provided deps both stay outside `mcdepImplementation` naturally, because the user just doesn't put them there.
- **The transitive set-difference handles the rare collision** (a lib that's both `mcdepImplementation` and a platform transitive) without user attention.

**Negative:**

- **Breaking change** for any consumer who used `excludeGroup`. Pre-publish, only the test-mods in this repo were affected; they were migrated in the same commit.
- **One more concept** for mod authors to learn (`mcdepImplementation` vs `implementation`). The Loom precedent (`modImplementation`) makes it familiar to Fabric authors at least.
- **The implicit `implementation extendsFrom mcdepImplementation` configuration** is non-obvious. A mod author reading `dependencies { mcdepImplementation("X") }` might wonder how `X` gets onto compile classpath; the plugin docs (README + `docs/README.md`) explain it.

## Alternatives considered

- **Keep `excludeGroup`, add a new mandatory exclusion per platform release.** Status quo + maintenance treadmill. Rejected.
- **Walk a fixed groupId allowlist** (only emit `org.typelevel:*`, `io.circe:*`, …). Brittle — every new mod-author-favorite library would need manual addition. The bucket approach is exactly equivalent to a per-mod allowlist without enumerating it.
- **Auto-detect "what's NOT on the parent classloader" at runtime.** Considered and rejected for dev-mode parity reasons (ADR-0007): in dev, Loom + MDG put more on the parent CL than ships in production, so a runtime auto-detect would silently skip downloads dev needs vs prod doesn't. The build-time set-difference doesn't have that asymmetry — `runtimeClasspath` in Gradle ≈ what production sees, and platform-served deps are stable across the two.

## Affected files

| Path | Role |
|---|---|
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/McdpProviderPlugin.java` | Defines the three configs + set-difference resolver. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/McdpProviderExtension.java` | DSL retired `excludeGroup`/`exclude`. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/GenerateMcdpManifestTask.java` | Glob-pattern exclusion logic deleted; manifest emits `mcdepManifest \ platformProvidedKeys`. |
| `test-mods/*/build.gradle.kts` | All `implementation(scala/cats/circe/...)` lines moved to `mcdepImplementation(...)`; `excludeGroup` calls removed. |

## Relationship to other ADRs

- Operates within **ADR-0003** (build-time dep resolution). What changed is *which* configuration we resolve; the producer pipeline (Aether → SHA-pinned manifest) is unchanged.
- Refines **ADR-0007** (dev-mode parity). The opt-in bucket makes the "what stays on Knot/MDG vs what gets stripped by `RunTaskClasspathPatch`" boundary clear at declaration time.

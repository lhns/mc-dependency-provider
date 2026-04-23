# ADR-0004 — Shared `deps-lib` library, build-tool-agnostic

**Status:** Accepted

## Context

Given the decision in ADR-0003 to resolve deps at build time, we need logic in two places:

1. **At mod build time**, by whatever build tool the mod author uses (Gradle today; sbt, Maven, or a standalone CLI later), to walk the resolved dependency graph and emit a manifest with `coords + url + sha256` for every artifact.
2. **At runtime**, in the provider, to read the manifest, download what's missing, and verify hashes.

These are two halves of the same concern: **the manifest format and the logic that produces and consumes it**. Duplicating this across a Gradle plugin and a runtime module would fragment the format; coupling the Gradle plugin directly into the runtime would tie the runtime to Gradle.

## Decision

A single `deps-lib/` module, build-tool-agnostic and loader-agnostic, written in plain Java. It contains:

- `Manifest` — POJO schema for `META-INF/mc-jvm-mod.toml`.
- `ManifestIo` — TOML parse/write via `tomlj`.
- `ManifestConsumer` — downloads listed URLs, verifies SHA256s, caches to `~/.cache/mc-lib-provider/libs/<sha256>.jar`. No resolver logic.
- `ManifestProducer` — given `(coords, repos)`, runs Apache Maven Resolver (Aether) to compute the transitive closure, extracts URLs and SHAs, emits a `Manifest`.
- `LibraryCache` — disk layout helpers.

The build emits two flavors:
- **`deps-lib-consumer`** — just Manifest + ManifestIo + ManifestConsumer + LibraryCache. Tiny (~50 KB minus tomlj). Used by the runtime.
- **`deps-lib-full`** — the above plus ManifestProducer + vendored Aether (~2 MB). Used by the Gradle plugin and any future sbt/Maven/CLI frontend.

The Gradle plugin vendors (shades) `deps-lib-full` under `io.github.mclibprovider.shaded.deps.*` for self-containment.

## Consequences

**Positive:**
- **One source of truth** for the manifest format and the resolve/download logic. If the schema evolves, both halves stay in sync.
- **Build-tool decoupling.** Nothing in `deps-lib` knows about Gradle. The Gradle plugin is a thin wrapper that queries Gradle's `Configuration.resolvedConfiguration` and hands the result to `ManifestProducer`. An sbt plugin would query sbt's resolver output and hand the same-shaped input to the same `ManifestProducer`. A Maven plugin, a CLI that reads a POM or an sbt `build.sbt`, whatever — all feasible without re-implementing resolution logic.
- **Runtime stays tiny.** The runtime depends only on `deps-lib-consumer`. Aether and its transitives never reach the shipped provider jar.
- **Testability.** `ManifestConsumer` and `ManifestProducer` are plain library code testable in isolation, without a Minecraft launch or a Gradle project.

**Negative:**
- **Build order constraint.** `deps-lib` must be built before anything else (`gradle-plugin`, `core`, `fabric`, `neoforge` all depend on it). Not a real problem for Gradle multi-project, but worth calling out.
- **Duplicate Aether** — the Gradle plugin vendors Aether at ~2 MB while Gradle itself has its own resolver. Waste, but the alternative (making the plugin call Gradle's resolver APIs directly) couples the logic to Gradle and makes sbt/Maven frontends harder. Worth the duplication.

## Alternatives considered

- **Put all the logic inside the Gradle plugin** — rejected. Locks us to Gradle; sbt users (a non-trivial portion of the Scala ecosystem) would need a parallel implementation.
- **Split `ManifestProducer` and `ManifestConsumer` into separate top-level modules** — rejected. They share the `Manifest` type and TOML serialization; splitting them forces a third "shared" module anyway. Cleaner to keep them together and let the Gradle build emit two jars from one source set.
- **Write the runtime in Scala and reuse existing Scala Maven libraries (Coursier)** — rejected. Drags Scala into the provider jar; see ADR-0003 consequences.

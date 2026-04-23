# ADR-0003 — Build-time dependency resolution with SHA-pinned manifests

**Status:** Accepted

## Context

A mod using the provider needs its Maven dependencies on the JVM at runtime. Two architectural choices for how resolution happens:

- **Build-time (option Z-X).** The mod author's build tool (Gradle, sbt, Maven) resolves the transitive dependency graph at mod-build time and bakes the result — a flat list of `coords + url + sha256` — into the mod jar. The runtime just downloads what's listed and verifies hashes. **No resolver at runtime.**
- **Runtime (option Z-Y).** The mod ships a coord-only manifest (possibly with version ranges) and the runtime runs a Maven resolver (Coursier or Aether) at boot, computing the closure and downloading what it decides. **Resolver embedded in the runtime.**

Minecraft itself uses the Z-X pattern: `version.json` carries a pre-resolved flat library list; the launcher only downloads and verifies. NeoForge's installer uses the same pattern. Neither ships a general-purpose runtime Maven resolver.

## Decision

Build-time resolution. The mod's manifest (`META-INF/mc-jvm-mod.toml`) lists every library including transitives, each entry carrying `coords`, `url`, and `sha256`. The runtime's `ManifestConsumer` only downloads listed URLs and verifies hashes. There is no Maven resolver code in the runtime.

Resolution is delegated to the mod author's build tool via the shared `deps-lib` library (see ADR-0004). The Gradle plugin walks `configurations.runtimeClasspath` and hands coords + repos to `deps-lib`'s `ManifestProducer`, which uses Apache Maven Resolver (Aether) to compute the closure. The plugin writes the resulting manifest into the mod jar.

## Consequences

**Positive:**
- **Runtime stays tiny.** The provider jar target of <300 KB is feasible because it has no resolver, no Aether, no Coursier, no Scala stdlib.
- **Deterministic.** The exact jars the mod author tested with are the jars users run. No silent version drift if upstream publishes a new patch between author build and user launch.
- **Supply-chain safety.** SHA256 per library is mandatory — verified on download — and all-or-nothing. Tampering is detected loudly.
- **Offline-capable.** `ManifestConsumer` checks the cache first; if all SHAs are present, no network is touched. The Gradle plugin's dev-mode pre-warm (ADR-0007) uses this to make `runClient` work without network after the first build.
- **Conflicts surface at build time.** If a mod's deps conflict (e.g. two libraries pulling incompatible cats), the mod author's Gradle surfaces the conflict with its normal tooling. The user never sees a runtime resolver failure.

**Negative:**
- **Requires a build-tool plugin.** Hand-writing a manifest with all transitives + SHAs is not viable; mod authors need the Gradle plugin (or a future sbt/Maven/CLI equivalent via the shared `deps-lib`).
- **Cross-mod version conflicts must be handled by isolation, not arbitration.** Two mods pinning different cats versions get different jars via per-mod classloaders (ADR-0001) rather than the provider picking one. In practice this is a feature — users don't get silent breakage — but it's a different model than a traditional Maven resolver would give.
- **First launch needs network.** The manifest lists URLs; if the cache is cold, downloads happen. Modpack authors shipping offline-ready bundles need a pre-populated cache (future `mc-lib-provider-prefetch` CLI).

## Alternatives considered

- **Z-Y: Coursier at runtime.** Coursier is Scala-native; bundling it drags the Scala stdlib into the provider jar and creates a maintenance burden across Scala minor upgrades. Plus the runtime-resolver drawbacks below.
- **Z-Y: Aether at runtime.** Pure Java, ~2 MB transitive, eliminates the Scala-shading problem. But still surfaces version conflicts at launch time rather than at mod build, silently drifts when upstream publishes between author build and user launch, and weakens the supply-chain story (we download whatever Aether picks, then verify — vs. pinning exactly what the author tested).
- **Z-Y could be added later** as an opt-in: same `deps-lib` library already contains `ManifestProducer` for build-time use; vendoring it into the runtime would enable coord-only manifests as a secondary code path. We've chosen not to do this for the MVP but the door is left open.
- **No plugin, hand-written manifests with no SHAs, download by coord** — rejected. The SHA pinning is most of the value; dropping it would make supply-chain attacks trivial and the approach equivalent to the worst of both worlds (no resolution, no safety).

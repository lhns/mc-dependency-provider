# ADR-0009 — Rejected alternatives record

**Status:** Accepted

## Context

The approach recorded in ADR-0001 through ADR-0008 was arrived at after evaluating several significantly different architectures. This ADR consolidates the "considered but rejected" options in one place, so a future maintainer doesn't have to rediscover why each was a dead end.

## Rejected: forked cats (status quo, à la Kotori's SLP)

**What it is:** Maintain a patched fork of cats with source-level renames — keyword packages like `cats.kernel.instances.byte` flattened into valid Java identifiers, lowercase `cats.instances.package$either$` objects renamed to `package$EitherI$`. Publish to a private Maven repo. Downstream mods link against the fork.

**Why rejected:** Every downstream mod that wants to use a library compiled against *vanilla* cats (circe, cats-effect published to Maven Central, anything else in the typelevel ecosystem) hits `NoClassDefFoundError` at runtime. The workaround — shadowJar-relocating vanilla cats into the mod's own private namespace — means every mod carries a 10 MB shaded cats and every author rediscovers the keyword-package relocation dance. The existence of this project is the rejection of this approach.

## Rejected: build-time shadowJar relocation per mod

**What it is:** Every Scala mod bundles its own cats/circe/etc., shadow-relocated to a private package to sidestep JPMS. Same mechanism FluidPhysics's cats-integration doc uses.

**Why rejected:** Works, but it's exactly the pain point we're trying to eliminate. 10 MB × N mods of duplicated bytecode. Every mod author has to learn the shadow plugin and the specific 8-keyword-package relocations. Version upgrades require every mod to rebuild. The ecosystem has no shared path.

## Rejected: runtime bytecode rewriting

**What it is:** Provider pre-rewrites downloaded lib jars (rename `byte/` → `_byte/` in the filesystem, rewrite constant-pool references in bytecode via ASM) before handing them to NeoForge. A second hook — `ILaunchPluginService` — rewrites mod bytecode at class-load time so the mod's references to `cats.kernel.instances.byte.*` resolve to the renamed packages.

**Why rejected:** Feasible and was an earlier version of this plan. Obsolete once we realized per-mod URLClassLoaders put dep classes in the unnamed module, where the JPMS keyword check doesn't fire (ADR-0002). Kept as a mental fallback if the unnamed-module claim ever proves wrong on a future JVM.

## Rejected: Coursier at runtime

**What it is:** Embed Coursier in the provider. Mods declare coord-only manifests; the provider resolves Maven at boot and downloads.

**Why rejected:** Coursier is Scala-native. Bundling it drags the Scala stdlib into the provider jar, making the "tiny, pure-Java provider" goal impossible. Plus the general runtime-resolver drawbacks (see below).

## Rejected: Apache Maven Resolver (Aether) at runtime

**What it is:** Same as above but with the pure-Java Aether instead of Coursier. No Scala to shade, ~2 MB transitive.

**Why rejected:** The Scala-shading problem goes away, but runtime resolution has its own drawbacks:
- **Silent version drift.** Between the mod author's build and the user's launch, upstream may publish a new patch. Aether can pick it; the user runs a version the author never tested.
- **Late conflict surfacing.** Version conflicts between mods surface at Minecraft launch time, where the user experience is poor. Build-time resolution surfaces them in Gradle where tooling exists to investigate.
- **Provider footprint.** 2 MB per distributed copy of the provider vs ~300 KB for the consumer-only path.

See ADR-0003 for the full argument. Same `deps-lib` library we build for Z-X can be extended to Z-Y (runtime resolution) later if needed, by vendoring `ManifestProducer` into the runtime. The door isn't closed; we've just chosen not to walk through it in the MVP.

## Rejected: OSGi (Felix or Equinox)

**What it is:** Embed a full OSGi framework. Each mod becomes an OSGi bundle with `Import-Package` / `Export-Package` metadata. OSGi's resolver picks bundle versions that satisfy cross-mod constraints; two mods on compatible cats ranges coalesce to one shared bundle.

**Why rejected:**
- **Bundle manifest burden.** Maven artifacts don't have OSGi headers. Tools like `bnd` can synthesize them by scanning bytecode, but the scan is imperfect for Scala macros and reflection-heavy code.
- **Embedding complexity.** Felix is ~500 KB of core but requires careful parent-classloader configuration to make NeoForge/Minecraft visible to bundles without leaking our own deps. System-packages allowlists are notoriously annoying to get right.
- **Learning curve.** Mod authors would face OSGi-specific error messages (*"Unable to resolve bundle revision X: missing requirement [osgi.wiring.package; filter=…]"*) instead of plain Gradle dep errors.
- **We don't need the value proposition.** OSGi's headline feature is version-aware sharing across compatible ranges. SHA-keyed coalescing (ADR-0006) handles the common case (identical pins); OSGi's extra power buys us sharing across 2.13.0 vs 2.13.1 or under `[2.0, 3.0)` ranges, which is a memory optimization, not a correctness requirement.

Documented in ADR-0006; OSGi is the natural evolution if SHA-keyed coalescing proves insufficient, but the jump is meaningful and unnecessary for MVP.

## Rejected: per-mod JPMS module layers

**What it is:** Each mod gets its own child `ModuleLayer` rooted at NeoForge's game layer, with its own `ModuleDescriptor`s for its deps. JPMS-native version isolation.

**Why rejected:** NeoForge puts all mods in a single `GAME` layer. Making per-mod layers work requires changes at the `ModuleLayerHandler` level, which we can't land upstream on our own schedule. Even if we did, the keyword-package problem (ADR-0002) still applies — JPMS rejects `byte` as a package name in any named-module context.

Per-mod URLClassLoaders give us the same isolation without upstream changes and without the keyword-package problem.

## Rejected: bundling all libs in the provider

**What it is:** Provider jar contains scala-library, scala3-library, cats-core, cats-kernel, cats-effect, circe, maybe more. Mods link against the versions the provider ships.

**Why rejected:** Locks every downstream mod to the provider's version choices. A mod that wants a newer cats can't get one without a new provider release. A mod that wants an older one hits upgrade issues. Cross-mod incompatibilities become inevitable as the version matrix grows. This is how Kotori's SLP currently works and is partly why it's painful to use.

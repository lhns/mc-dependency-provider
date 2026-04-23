# ADR-0001 — Per-mod URLClassLoaders for dependency isolation

**Status:** Accepted

## Context

Scala (and increasingly Java) mods need external Maven libraries — cats, circe, Jackson, etc. A loader that supports such mods has to decide how those libraries are exposed to the JVM at runtime.

Three candidate mechanisms exist:

1. **Shared classpath** (or shared JPMS game layer) — every mod's deps on one classpath. Matches how NeoForge's `javafml` works today. Simple, but forces all mods to agree on a single version of every shared library; when they don't, one mod silently gets the wrong version and breaks.
2. **Per-mod JPMS module layers** — each mod in its own child `ModuleLayer` with its own deps. Provides isolation, but NeoForge doesn't natively support per-mod layers (all mods go into the `GAME` layer). Adding that would require `ModuleLayerHandler`-level changes we can't land upstream on our own schedule. Also inherits the JPMS keyword-package problem documented in ADR-0002.
3. **Per-mod plain classloaders** — each mod in its own `URLClassLoader` with the mod jar + its deps on the URL list. Standard pattern used by OSGi, Jenkins, IntelliJ, JBoss Modules, and effectively every plugin system in the JVM world.

Additionally, library jars on the NeoForge game layer suffer the `Invalid package name: 'byte'` failure (see ADR-0002) — ruling out option 1 for cats-using mods regardless.

## Decision

Each mod discovered by the provider gets its own `ModClassLoader`, a `URLClassLoader` subclass. Its URL list contains the mod jar and every dep jar listed in the mod's manifest. Its parent is the provider's own classloader, which transitively reaches Minecraft, NeoForge/Fabric, and the JDK.

Delegation policy:

- **Parent-first** for a fixed prefix list: `java.*`, `javax.*`, `jdk.*`, `sun.*`, `com.sun.*`, `net.minecraft.*`, `net.neoforged.*`, `net.fabricmc.*`, `com.mojang.*`, plus whatever the provider itself exports (e.g. an `io.github.mclibprovider.api.*` surface for mod-to-mod coordination).
- **Parent-first** additionally for any prefix the mod declares in its manifest's `shared_packages` list. This is how Mixin interoperability (ADR-0008) works: Java interfaces and mixin classes live under a shared prefix and are the *same `Class`* in both NeoForge's game layer and the per-mod classloader. Typical use: `shared_packages = ["de.lolhens.fluidphysics.api"]`. Without this, an interface declared in the mod jar would be defined twice — once by NeoForge's loader and once by the per-mod loader — and cross-loader interface dispatch would fail with `ClassCastException`.
- **Child-first** for everything else, so the mod's own bytecode and its deps resolve through the per-mod URL list before falling back to the parent.

## Consequences

**Positive:**
- Mod A can use `cats-core:2.10` while mod B uses `cats-core:2.13`. Each mod's `cats.Foo` is a distinct `Class` from a distinct loader, linked against its own copy. No conflict, no arbitration, no `NoSuchMethodError` at launch.
- Dep jars are not subjected to JPMS validation (ADR-0002).
- NeoForge and Fabric converge on the same mechanism at this layer — the classloader logic in `core/` is loader-agnostic.
- No upstream changes required to NeoForge or Fabric.

**Negative:**
- Mod classes loaded through `ModClassLoader` don't pass through NeoForge's `TransformingClassLoader`, so Mixin does not transform them directly. For mixing into Minecraft internals, authors use a bridge pattern (Java interface + per-mod-loaded impl) — see ADR-0008.
- Duplicate copies of the same library across mods when SHAs differ. SHA-keyed coalescing (ADR-0006) mitigates the common case where SHAs match.
- Cross-mod APIs that pass Scala types (e.g. `List[String]`) across a classloader boundary get `ClassCastException`. Cross-mod APIs should use Java-native types or explicit serialization. This is the standard cross-classloader caveat of any plugin system.
- `shared_packages` entries are parent-first everywhere they appear, including from other mods. Two mods declaring overlapping shared prefixes can't both "own" them — surfaces as a conflict at provider boot.

## Alternatives considered

- **Shared classpath / single game layer** — rejected. JPMS breaks cats before we even get to version conflicts, and version conflicts themselves are a significant real-world problem.
- **Per-mod JPMS module layers** — rejected. Would require patching NeoForge's `ModuleLayerHandler`. Classloaders give us the same isolation without upstream work.
- **OSGi (Felix / Equinox)** — deferred. See ADR-0009. OSGi's extra value (version-aware sharing across compatible ranges) isn't needed at this stage and its costs are significant.

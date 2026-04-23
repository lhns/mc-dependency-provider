# ADR-0006 — SHA-keyed classloader coalescing as the only optimization

**Status:** Accepted

## Context

ADR-0001 gives each mod its own `ModClassLoader` with its own dep jars. In isolation, this is correct. In practice, many Scala mods will pin identical versions of common libraries — `cats-core_3:2.13.0`, `circe-core_3:0.14.10`, and so on. If ten mods each pin the exact same cats jar, we end up with ten URLClassLoaders each loading the same classes from the same jar bytes — wasting memory and classloader overhead for no correctness benefit.

Two optimization strategies to consider:

1. **Exact-duplicate coalescing.** If two mods declare the same `(coords, sha256)` pair, they share one URLClassLoader for that artifact. Classes in it are `Class`-identical across both mods.
2. **Version-range coalescing.** If mod A wants `cats-core:[2.10,3.0)` and mod B wants `cats-core:[2.13,)`, a resolver could pick `2.13` and share it. This is OSGi's headline feature.

Option 2 is strictly more powerful but requires either a runtime resolver (rejected in ADR-0003) or OSGi's bundle-manifest infrastructure (rejected in ADR-0009). Option 1 requires no resolver — just a `Map<String, URLClassLoader>` keyed by SHA.

## Decision

Implement option 1 only. `LoaderCoordinator` maintains a `Map<Sha256, URLClassLoader>` across the boot session. When building a mod's `ModClassLoader`:

```
for each library L in mod.libraries:
    libLoaders.add(libraryLoaderCache.computeIfAbsent(
        L.sha256,
        sha -> new URLClassLoader(new URL[]{L.cachedJar.toUri().toURL()}, providerLoader)))
mod.classLoader = new ModClassLoader(mod.modJar, libLoaders, providerLoader)
```

Each library becomes a singleton URLClassLoader keyed by SHA256. A mod's `ModClassLoader` chains across those library loaders + its own mod jar.

If two mods declare different SHAs for the same `coords` — whether due to a version bump or a pinning disagreement — they get separate library loaders. No reconciliation, no picking. Each mod gets exactly what it asked for.

## Consequences

**Positive:**
- **Zero memory waste for the overwhelming common case** — mods in a modpack that track the same ecosystem release pin the same SHAs and share loaders automatically.
- **No resolver needed.** The implementation is ~20 LOC on top of ADR-0001's classloader. No OSGi, no Aether, no Coursier at runtime.
- **Classes are `Class`-identical across mods** that share a lib — mod A and mod B's `cats.Foo` is the exact same `Class` object, not just isomorphic. Cross-mod APIs using shared-lib types work naturally.

**Negative:**
- **No coalescing across version-compatible-but-different pins.** If mod A pins `2.13.0` and mod B pins `2.13.1`, each gets its own copy even though one version would satisfy both. Memory waste, small in absolute terms (a library might be 1–10 MB; tens of mods all pinning minor-version-different is uncommon).
- **Scala stdlib duplication.** Mods pinning `scala3-library_3:3.5.2` vs `3.5.3` don't share the stdlib. Both run fine via per-mod isolation, but it costs ~15 MB per distinct version. Mitigated by ecosystem convention (mod authors typically track recent releases), not by the provider.

## Alternatives considered

- **No coalescing at all.** Simpler but wasteful. The SHA-key map is trivial and demonstrably helpful.
- **OSGi-style version-range coalescing.** Requires bundle manifests or a runtime resolver. Rejected in ADR-0009 and would be a significant complexity uptick for a niche benefit.
- **Coalesce all libs at runtime by picking highest version per coord.** Breaks the determinism guarantees of ADR-0003 — mod authors pin exact versions deliberately. Silently upgrading is worse than not coalescing.

## Upgrade path

If real-world use shows significant memory pressure from near-duplicate pins, we can add a second coalescing pass: after SHA coalescing, detect groups of `coords`-matching loaders where all declared versions satisfy the union of their declared ranges, and pick the highest. This remains far simpler than OSGi because it operates on pre-resolved, flat manifests rather than bundle manifests with imports. Not needed for MVP.

# ADR-0010 — Opt-in stdlib version promotion

**Status:** Accepted (policy shipped; platform integration deferred until post-v0.1 memory profiling)

## Context

ADR-0006 coalesces libraries only when two mods pin the *exact same* SHA-256. This is the right default: it preserves determinism (mod authors get exactly what they pinned) and costs ~20 LoC. The known downside, called out explicitly in ADR-0006 ("Upgrade path"): a modpack with twenty Scala mods, each pinning a slightly different patch version of `scala3-library_3`, produces twenty separate stdlib loaders — roughly 15 MB per distinct version for the stdlib alone.

For most libraries that duplication is intentional. cats 2.10 and cats 2.13 genuinely behave differently; coalescing across them would silently rewrite one mod's code. But the stdlib is different in two ways:

1. Each mod's classes link at compile time against its own stdlib version. At runtime the JVM resolves `scala.Option` through the mod's own loader chain, so `scala.Option` identity never crosses mod boundaries. No mod has an interop contract that depends on "my stdlib is the exact one you compiled with."
2. Patch bumps within a stdlib major are, by ecosystem convention, source- and bytecode-compatible. A mod compiled against 3.5.2 runs correctly against 3.5.3.

Under those conditions, serving every mod from the highest-pinned patch version is a pure memory win.

## Decision

Ship `StdlibPromotion` as an opt-in policy class in `core/`. Do **not** alter the default `LoaderCoordinator.register` path. Platform adapters that want the optimization invoke a three-step flow:

```
// 1. Collect all mods' manifests up-front (platform adapter scans its mod discovery).
List<Manifest> all = ...;

// 2. Pick highest version per promoted coord stem.
StdlibPromotion policy = StdlibPromotion.defaults();
Map<String, Manifest.Library> winners = policy.selectPromotions(all);

// 3. Build one shared loader per winner; strip those libs from each mod's per-mod
//    list so they're served from the parent chain instead of duplicated.
for (each mod) {
    List<Library> perMod = policy.stripPromoted(mod.manifest, winners);
    coordinator.register(mod.id, /* reduced manifest */, mod.jar, /* reduced lib paths */);
}
```

Promoted stems default to Scala 3, Scala 2, and the common Kotlin stdlib variants — the libraries most likely to be pinned across many mods with patch drift. Callers can pass any `Set<String>` of `groupId:artifactId` stems.

Version comparison is a tailored Maven-flavored comparator: numeric components numerically, qualifier tail by release > RC > milestone > beta > alpha > snapshot. Good enough for stdlib drift; not a general-purpose Maven resolver.

## Consequences

**Positive:**
- **Opt-in.** The default path is unchanged; a misconfigured promotion set can't silently break an existing install. Adapters only gain the behavior when they explicitly batch-register.
- **Bounded surface.** `StdlibPromotion` is a data-only policy object. No changes to `LoaderCoordinator.register`, `ModClassLoader`, or the manifest schema. Removing the feature later means deleting one file + a callsite.
- **Determinism preserved for non-stdlib.** cats, circe, arbitrary user libs — all still per-mod by SHA. Only the explicit promoted set participates.
- **Observable.** The selected winner per stem is a concrete data structure the platform adapter logs; no hidden heuristics firing at runtime.

**Negative:**
- **Correctness depends on the assumption that patch-bumps within a promoted stem are bytecode-compatible.** True for the listed stdlibs; not true in general, which is why the policy is stem-scoped rather than a blanket "coalesce anything with the same groupId:artifactId." A mod author who deliberately needs a specific Scala stdlib patch (e.g. to avoid a known bug) loses that pin if another mod in the same pack pins higher. Escape hatch: platform adapter can build `StdlibPromotion` with a trimmed stem set or none at all.
- **One more concept in the architecture.** Mod authors need to understand both per-mod SHA isolation (ADR-0006) and stdlib promotion (this ADR). Worth the tradeoff for the memory recovery at scale.
- **Platform integration is non-trivial.** The current `register(mod, manifest, jar, libs)` API runs per-mod as platform callbacks fire; batch promotion needs the adapter to collect every mod before first registration. On Fabric the PreLaunch pass already iterates all mods; on NeoForge the ordering is less obvious. Defer until M4 runServer is green and we can measure real memory pressure.

## Alternatives considered

- **Expose version-range coalescing (OSGi-style).** Rejected in ADR-0009. Bundle manifests + resolver logic is too much machinery for a problem that's much smaller in practice than the OSGi proponents suggest.
- **Make promotion the default, promoting all libs.** Breaks determinism for libraries that have genuine breaking patch changes (e.g. tapir, fs2 historically). Not safe without per-library opt-in, at which point it's equivalent to this policy anyway.
- **Promote at build time via the Gradle plugin instead of at load time.** Would require the plugin to resolve against every *other* mod in the final modpack — information not available at build time. The load-time path is the only place that sees the full set.

## Upgrade path

If the platform integration lands and real modpacks show promotion working well, consider:

1. Extending `StdlibPromotion` with a set of *compatibility-banded* promotions (e.g. scala3-library_3 promotes within `3.x.y`, but `2.13.14` and `3.5.2` are always separate).
2. Surfacing the selected winners in the platform adapter's startup log so modpack authors can audit what was coalesced.
3. A Gradle plugin check that warns when a mod's explicit pin differs from the likely highest-version-in-pack (requires modpack-level coordination; optional).

# ADR-0013 — NeoForge-faithful entry init via `@Mod` + FML helpers

**Status:** Accepted — shipped in `17cccec` (`@Mod` discovery + `AutomaticEventSubscriber.inject`) on top of `c48869b` (`IEventBus` + `Dist` reaching the entry ctor). The construction-timing aspect (eager construct in `loadMod`, then wrap) is **superseded by [ADR-0017](0017-modcontainer-as-ctor-arg.md)**, which moves construction into `McdpModContainer.constructMod()` to enable `ModContainer` in the entry ctor. The `@Mod`-discovery + bag-dispatch decisions in this ADR stand unchanged.

## Context

mc-lib-provider's NeoForge entry-init originally invented a contract: mod authors declared their entry class via a `[modproperties.<modId>] entrypoint = "..."` toml property and the language loader passed only `IModInfo` to the entry constructor. ADR-0005 also kept the door open for an `(IModInfo)`-shaped ctor that no other NeoForge loader uses.

This had three concrete problems:

1. **`@Mod` discovery missing.** Every NeoForge tutorial documents `@Mod("modid")` on the entry class. Mods porting from `javafml` had to learn a new mclibprovider-only mechanism.
2. **No `IEventBus` in the ctor.** Mods that need to register `RegisterEvent` / `FMLClientSetupEvent` listeners — i.e. essentially every non-trivial NeoForge mod — couldn't, because those events are gated to the per-mod bus and fire before any global event the mod could otherwise hook into. The mc-skylands consumer documented this gap in its own `mclibprovider-neoforge-gap.md`.
3. **`@EventBusSubscriber` silently inert.** FML scans for `@EventBusSubscriber`-annotated classes only from `FMLModContainer`. Our `McLibModContainer` did not run that scan, so the standard static-handler annotation produced no listeners under mclibprovider.

The reason mclibprovider has a custom `IModLanguageLoader` at all is **classloader isolation**: the mod's entry class transitively references libs (`cats.Functor`, `circe.parser`, `kotlinx.coroutines`) that live only in our per-mod `URLClassLoader`. If FML's `javafmlmod` constructed the entry, JVM linking would `NoClassDefFoundError` long before any mod code runs. So we cannot delegate construction wholesale to FML — the entry class must be loaded through our CL.

But: **within that constraint**, every other piece of FML's `@Mod` protocol can be matched, and parts of FML's machinery are public and reusable directly.

## Decision

Match FML's `javafml` protocol on the user-visible surface. Reuse FML's published helpers for the substantial machinery; hand-roll only what we must.

**FML pieces inspected** (NeoForge `loader-4.0.31.jar`):

- `net.neoforged.fml.common.Mod` — annotation, `value()` (modId) and `dist()`. Public; on FML's compileOnly classpath.
- `net.neoforged.neoforgespi.language.ModFileScanData#getAnnotatedBy(Class, ElementType)` — already passed to our `loadMod`; returns `Stream<AnnotationData>` exposing each annotation's `clazz()` (ASM `Type` of the annotated class) and `annotationData()` (`Map<String, Object>` of annotation params).
- `net.neoforged.fml.javafmlmod.AutomaticEventSubscriber#inject(ModContainer, ModFileScanData, Module)` — public static. The actual `@EventBusSubscriber` scanner FML uses internally.

**What we adopted:**

1. **`@Mod` annotation discovery** for the entry class. `loadMod` calls `scanResults.getAnnotatedBy(Mod.class, ElementType.TYPE).filter(value().equals(modId)).map(clazz().getClassName()).findFirst()` — three lines, identical source as `FMLJavaModLanguageProvider`. The entry FQN comes from there, then `Class.forName(fqn, true, ourModClassLoader)` so the class is defined by *our* loader.

2. **Pre-built per-mod `IEventBus` + `Dist`** before construct(). The mod's ctor receives them as args; the `McLibModContainer` publishes the same `IEventBus` instance NeoForge's dispatcher routes through. Identity-preserved so listeners registered in the ctor receive events.

3. **Constructor signature support** matching FML — `()`, `(IEventBus)`, `(IEventBus, Dist)`, `(IEventBus, ModContainer)`, `(IEventBus, ModContainer, Dist)`. `ModContainer` is `null` during initial construction (chicken-egg with our build-then-wrap flow); the bag-based dispatch in `JavaEntrypointAdapter` skips null-typed slots, so ctors needing a non-null `ModContainer` aren't matchable. Mods that need the live container fetch it via `ModList.get().getModContainerById(modId)` from any post-construction event — same workaround any FML mod would use to reach a sibling mod's container.

4. **`@EventBusSubscriber` scanning via `AutomaticEventSubscriber.inject`** directly. Called immediately after construction with our `McLibModContainer`, the existing `scanResults`, and `entryClass.getModule()`. FML walks the scan data, filters by modId + Dist, registers each handler against the right bus. Future FML annotation features land for free.

**What we kept hand-rolled:**

- The bag-based ctor dispatch in `JavaEntrypointAdapter` (matches params by type from the context bag, most-arity wins). Replacing it with a fixed-shape `EntryContext(IEventBus, ModContainer, Dist)` record was considered but rejected — that record would require `core/` to import NeoForge types, breaking the platform-agnostic abstraction Fabric also depends on. The bag carries only `(eventBus, dist)` from the NeoForge call site; all five FML ctor shapes are subsets and dispatch correctly.

**What was removed:**

- The `[modproperties.<modId>] entrypoint = "fully.qualified.ClassName"` toml property (`McLibLanguageLoader.loadEntryClass`'s former source).
- `(IModInfo)`-only ctor support — `IModInfo` is no longer in the context bag. No FML mod accepts `IModInfo` via ctor; the few mods that need it can call `info` getters from inside event handlers post-construction.

**What we considered and rejected:**

- **Wholesale reuse of `FMLModContainer`**. Its public ctor is `(IModInfo, List<String> modClassNames, ModFileScanData, ModuleLayer)` — but the `ModuleLayer` parameter wants a real layer whose loader resolves classes for us. Our `ModClassLoader` is an unnamed-module `URLClassLoader`. Reusing `FMLModContainer` would mean manufacturing a synthetic `ModuleLayer` per mod (with `ModuleFinder` over the mod jar, single anonymous module, our CL attached) — doable but adds complexity and ties us tightly to `FMLModContainer`'s internal lifecycle expectations. `AutomaticEventSubscriber.inject` alone covers the substantial reuse without that coupling.

## Consequences

**Positive:**

- **Vanilla NeoForge UX.** A mod author switching to mclibprovider only changes one line in `neoforge.mods.toml` (`modLoader = "mclibprovider"`) and one set of dep declarations (`mcLibImplementation(...)`). The `@Mod` annotation, ctor signatures, and `@EventBusSubscriber` all behave identically to vanilla `javafml`. mc-skylands' `SkylandsNeoForge(modBus: IEventBus, ...)` ports verbatim.
- **Smaller maintained surface.** `@EventBusSubscriber` scanning is delegated to FML (`AutomaticEventSubscriber.inject`), so future FML annotation features land without changes here. Future bug fixes in FML's scanner apply automatically.
- **Standard error path.** If a mod sets `modLoader = "mclibprovider"` but forgets `@Mod("modid")`, the loader throws an explicit `IllegalStateException` naming the missing annotation — easier to diagnose than a missing toml property.

**Negative:**

- **Breaking change** for any consumer that relied on `[modproperties.<modId>] entrypoint = "..."` or an `(IModInfo)` ctor. mclibprovider hadn't been published to Maven Central, so the only consumers are this repo's test-mods (migrated in the same commit) and mc-skylands' draft port (not yet on the new shape — they were waiting for this).
- **One more dependency on FML internals.** `AutomaticEventSubscriber.inject` is a `public static` method but `net.neoforged.fml.javafmlmod` is an internal-feeling package. If FML moves the class, we'd need to either re-roll the scan (~60-80 LOC of bytecode-annotation parsing) or vendor it. The benefit (free-tracking of FML's evolving annotation surface) outweighs the brittleness.

**Neutral:**

- `ModContainer` not reachable in the ctor. Same as before this ADR — mods that need it use `ModList.get().getModContainerById(modId)` post-construction. Dropping the chicken-egg constraint would require flipping our flow to FML's deferred `constructMod()` callback, which is a separate ADR-scale change with no current motivation.

## Affected files

| Path | Change |
|---|---|
| `neoforge/src/main/java/io/github/mclibprovider/neoforge/McLibLanguageLoader.java` | `@Mod` discovery via `ModFileScanData`; `IEventBus` + `Dist` built before construct(); `AutomaticEventSubscriber.inject` after; `entrypoint` toml lookup removed. |
| `neoforge/src/main/java/io/github/mclibprovider/neoforge/McLibModContainer.java` | Accepts pre-built `IEventBus` so the ctor and dispatcher share one instance. |
| `core/src/main/java/io/github/mclibprovider/core/JavaEntrypointAdapter.java` | Bag-based dispatch (kept, was c48869b's change). |
| `core/src/main/java/io/github/mclibprovider/core/EntrypointAdapter.java` | Javadoc — context-bag semantics. |
| `test-mods/{neoforge,kotlin}-example/.../neoforge.mods.toml` | `modproperties.entrypoint` lines removed. |
| `test-mods/{neoforge,kotlin}-example/...` | Entry classes annotated `@Mod("…_example")`. |
| `docs/adr/0008-mixin-via-bridge-pattern.md` | Three-path resolution order documented (c48869b). |

## Relationship to other ADRs

- Refines **ADR-0005** (pluggable `EntrypointAdapter`). The adapter still dispatches by `lang`; what changed is the *shape* of the context passed to it (a typed bag, not just `IModInfo`) and how the entry class FQN is discovered (annotation scan, not toml property). 0005 is not superseded — its core decision still stands.
- Operates within **ADR-0001**'s constraint (per-mod `URLClassLoader`s). The reason we cannot delegate construction wholesale to FML's `FMLModContainer` is that the entry class must be loaded through our CL; that follows directly from ADR-0001.

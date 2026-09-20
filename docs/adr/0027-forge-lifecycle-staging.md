# ADR-0027 — Forge mod construction moves to the `CONSTRUCT` stage; classloader setup stays in `loadMod`

**Status:** Accepted. Applies ADR-0017's construction-timing decision to the Forge adapter (MC 1.18.x and 1.20.x, shared source). ADR-0013's `@Mod` discovery and bag-dispatch decisions stand unchanged.

## Context

The Forge adapter loaded mods and then never wired the mod lifecycle. `net.minecraftforge.fml.ModContainer.acceptEvent` is a no-op on the base class, and `McdpModContainer` did not override it, so **no `IModBusEvent` ever reached an mcdp-loaded mod** — `FMLCommonSetupEvent`, `FMLClientSetupEvent`, `InterModEnqueueEvent`, `FMLLoadCompleteEvent`, all silently dropped. The container also had no `IEventBus` at all, and called `adapter.construct(entryClass)` with an empty bag, so ADR-0017's `(IEventBus, ModContainer, Dist)` entry-constructor shape was unavailable on Forge.

The delivery mechanism is `ModContainer.buildTransitionHandler`, verified by `javap -c` against `fmlcore-1.18.2-40.3.12` and `fmlcore-1.20.1-47.4.20`. Both bands run the identical sequence per stage: `ModLoadingContext.setActiveContainer(this)` → `activityMap.getOrDefault(modLoadingStage, noop).run()` → `acceptEvent(eventGenerator.apply(this))`. The two `protected` hooks a subclass needs — the mutable `activityMap` and `acceptEvent` — have byte-identical shapes on 4.0.x and 7.x. The 1.20 signature of `buildTransitionHandler` gained a `ProgressMeter` parameter, but that method is `public static` and FML-internal; it is not something we override.

## Decision

Split the container into two phases, as vanilla `FMLModContainer` does.

**Constructor (FML's `loadMod` dispatch)** keeps all the mcdp-side registration: manifest read, library resolution, `ModClassLoader` construction, `McdpProvider.registerMod`, bridge-manifest registration. It additionally resolves the entry class with `initialize = false` and builds the per-mod `IEventBus`, then registers `activityMap.put(ModLoadingStage.CONSTRUCT, this::constructMod)`. No mod code runs.

**`constructMod()`, driven by FML at the `CONSTRUCT` stage**, instantiates the entry class with the bag `[eventBus, this, dist]`. `acceptEvent` is overridden to `eventBus.post(e)`.

The dist comes from `net.minecraftforge.forgespi.Environment.get().getDist()`, not `FMLEnvironment` — `Environment` is in forgespi (already a `compileOnly` on both bands) with an identical signature on 4.0.11 and 7.1.6, whereas `FMLEnvironment` lives in `fmlloader`, which neither band depends on. `Environment.get()` returns a plain static field, so it is null outside FML; `Dist` is then dropped from the bag, exactly as ADR-0017 does for tests.

The bus recipe is restricted to the `BusBuilder` methods present identically on eventbus 5.0.7 (the 1.18 band, where `BusBuilder` is a final class) and 6.2.33 (the 1.20 band, where it is an interface): `builder()`, `setExceptionHandler()`, `markerType(IModBusEvent.class)`, `build()`. `allowPerPhasePost()` is NeoForge-only and is not used. Because the bands compile separately, the class-vs-interface difference is invisible to the shared source.

Unlike vanilla Forge, whose exception handler only logs, ours logs and rethrows: a listener failure in an mcdp-loaded mod should land in FML's error sheet rather than scroll past in the log.

## Consequences

**Positive:**
- Lifecycle events reach mcdp-loaded Forge mods for the first time; the adapter reaches parity with NeoForge.
- Entry classes can declare `(IEventBus)`, `(ModContainer)`, `(IEventBus, ModContainer, Dist)` and every subset, matching ADR-0017.
- Entry-class failures now surface during `CONSTRUCT` with FML's per-mod error reporting, instead of during jar scanning.
- Registration still happens for **every** container before **any** mod is constructed. That two-pass separation is what a cross-mod pass such as stdlib promotion needs, and it now falls out of the structure rather than having to be bolted on.

**Negative:**
- Mod side effects move later in the boot sequence. Anything that previously relied on an mcdp Forge mod's constructor having run before FML's `CONSTRUCT` step would break — nothing in-tree does.
- `getMod()` returns `null` between `loadMod` and `CONSTRUCT`. Vanilla `FMLModContainer` has the same window.
- `@EventBusSubscriber` is still not auto-registered on Forge: `AutomaticEventSubscriber` lives in the `javafmllanguage` artifact, which is not on either band's compile classpath. Mods register listeners explicitly via the injected bus. Unchanged from before, but now the only remaining gap.

## Alternatives considered

- **Keep construction in the container constructor and only add `acceptEvent`.** Cheapest possible change, and it would have delivered events. Rejected: it leaves mcdp's Forge construction order permanently different from vanilla's and from NeoForge's, it gives up the all-registered-before-any-constructed property, and the entry class would still be instantiated before FML has an active container, so anything reading `ModLoadingContext` from a constructor would see the wrong mod.
- **Depend on `fmlloader` for `FMLEnvironment.dist`.** Rejected: a new per-band dependency, unverifiable from the local artifact cache, for information forgespi already exposes.
- **Share a single bus across all mcdp mods.** Rejected: FML routes events per container, and `@EventBusSubscriber`-style per-mod filtering assumes per-mod buses.

# ADR-0017: NeoForge mod construction inside `ModContainer.constructMod()`

**Status:** Accepted — shipped before v0.1.0. Supersedes the construction-timing aspect of ADR-0013 (the `@Mod` discovery + bag-dispatch decisions in 0013 stand unchanged).

## Context

ADR-0013 established that mcdpprovider mods follow vanilla NeoForge's `@Mod`-annotation discovery and bag-based constructor dispatch (`{IEventBus, Dist, ...}` matched by `isAssignableFrom`). The remaining gap was *when* construction happens.

Through ADR-0013 our `McdpLanguageLoader.loadMod` constructed the mod instance eagerly, then wrapped it in a freshly-built `McdpModContainer(info, instance, eventBus)`. That wrapper-after-construction order makes it impossible to pass the container into the entry's constructor — the container can't exist before the instance does. As a result, vanilla NeoForge ctor signatures like `(ModContainer)` or `(IEventBus, ModContainer, Dist)` couldn't be expressed by mcdpprovider mods.

Vanilla NeoForge solves the chicken-and-egg through `ModContainer`'s lifecycle:

> `protected void constructMod() {}` — "Function invoked by FML to construct the mod, right before the dispatch of `FMLConstructModEvent`." (`net.neoforged.fml.ModContainer`, NeoForge `loader-4.0.x`)

`FMLModContainer` builds itself first (with bus + entry-class recipe but no instance), then overrides `constructMod()` to invoke the entry's single public constructor with `this` (the container) plus `IEventBus`, `Dist`, and `FMLModContainer.class` available in a fixed allowed-args map. The instance is discarded — only its side effects (bus subscriptions, `@EventBusSubscriber` injection) matter.

## Decision

Move mcdpprovider's mod construction into `McdpModContainer.constructMod()`, mirroring `FMLModContainer`. `McdpLanguageLoader.loadMod` returns an *un-constructed* container; FML drives `constructMod()` at the `CONSTRUCT` lifecycle step.

The container builds the per-mod `IEventBus` itself (every `ModContainer` subclass must — `ModContainer.getEventBus()` is `abstract`, and FML provides no shared factory). The recipe is identical to `FMLModContainer`'s: `BusBuilder.builder().setExceptionHandler(...).markerType(IModBusEvent.class).allowPerPhasePost().build()`.

Inside `constructMod()`, the bag passed to `EntrypointAdapter.forLang(lang).construct(entryClass, bag)` contains `[eventBus, this, dist]` — including `this` lets the bag matcher (`JavaEntrypointAdapter.fillFromBag`) fill any ctor parameter typed `ModContainer` or `McdpModContainer` (the matcher uses `isAssignableFrom`, so the actual type and any of its supertypes match). Subsets work: a ctor declaring just `(ModContainer)` matches; a no-arg ctor matches; `(IEventBus, ModContainer)` matches; and so on. Order doesn't matter — the matcher walks declared parameters and picks the first assignable bag entry.

`Dist` is included only when `FMLEnvironment.dist != null`. Production code paths always satisfy this (FML sets it before any `loadMod` call). The conditional exists so unit tests — which don't go through FML init and can't write `static final FMLEnvironment.dist` via reflection — still exercise the bag-matching code path with subset ctors.

`AutomaticEventSubscriber.inject(this, scanResults, entryClass.getModule())` runs *after* the entry ctor returns, matching `FMLModContainer`'s order. A failure during inject is best-effort-swallowed (consistent with the prior loadMod behavior in ADR-0013); a malformed `@EventBusSubscriber` shouldn't fatally fail mod load.

We keep `getModInstance()` on the container even though vanilla discards the instance. Rationale: the unit tests need to assert on it. Production paths (`LoaderCoordinator`, `McdpProvider`, MixinConfigScanner pre-registration) don't reference the instance — they key off `modId`/`ModClassLoader`.

### What the consumer sees

A NeoForge mod using mcdpprovider can now write any of these entry constructors and they all resolve:

```java
@Mod("my_mod")
public final class MyMod {
    public MyMod() { /* simplest, always supported */ }
}

@Mod("my_mod")
public final class MyMod {
    public MyMod(ModContainer container) { /* new — was impossible before this ADR */ }
}

@Mod("my_mod")
public final class MyMod {
    public MyMod(IEventBus bus, ModContainer container, Dist dist) { /* full vanilla shape */ }
}

@Mod("my_mod")
public final class MyMod {
    public MyMod(IEventBus bus, McdpModContainer container) {
        /* the matcher walks isAssignableFrom, so the McdpModContainer subclass type works too */
    }
}
```

### Pre-CONSTRUCT work that stays eager in `loadMod`

These run before the container is returned (so before `constructMod()` fires). They don't depend on the entry instance:

- Manifest read (`ManifestIo.read`).
- Library download (`ManifestConsumer.resolveAll`).
- Stdlib promotion bootstrap (first-call walks `LoadingModList`, builds shared promoted-lib classloader).
- `ModClassLoader` registration (`LoaderCoordinator.register`).
- `McdpProvider.registerMod(modId, loader)`.
- Mixin owner pre-registration from `[[mixins]]` entries (ADR-0008 path 2).
- Entry class load (`Class.forName(fqn, true, loader)`).

Errors during these still attribute to the LOAD lifecycle stage, which matches FML's expectations — `loadMod` is the time to surface manifest/library/classloader problems.

## Consequences

**Positive:**
- mcdpprovider mods reach full vanilla-parity in entry-ctor expressiveness — no missing argument shape vs javafml mods.
- Construction errors get attributed to FML's `CONSTRUCT` stage in the loading-error reporter, which surfaces them in the right place in the FML error UI.
- Lifecycle timing exactly matches `FMLModContainer`: ctor runs immediately before `FMLConstructModEvent` is dispatched on the per-mod bus, so `RegisterEvent`/`FMLClientSetupEvent` listeners that the ctor subscribes can fire on the correct order.

**Negative:**
- Slight increase in McdpModContainer surface (it now stores `entryClass`, `lang`, `scanResults`, `modInstance`).
- A failure in the entry ctor now throws from `constructMod()` instead of `loadMod`. FML wraps the exception into a `ModLoadingException` with the right stage; ours throws plain `IllegalStateException` with a `mcdepprovider:`-prefixed message. Slightly less integration with FML's reporter than vanilla, but the message + cause-chain is enough for users to find the bug.

**Neutral:**
- `getModInstance()` survives for tests. Vanilla discards; we keep it but no production caller references it.

## Alternatives rejected

- **Path A (eager construct, settable instance).** Build the container with `null` instance, append it to the bag, then `setMod(instance)`. Simpler patch (~15 lines), but doesn't move construction to the lifecycle-correct moment, and FML's reporter still attributes errors to the wrong stage. Rejected for vanilla parity.
- **Custom allowed-args map (vanilla-style).** Vanilla uses `Map.of(IEventBus.class, eventBus, ModContainer.class, this, FMLModContainer.class, this, Dist.class, ...)` with strict equality. Our `JavaEntrypointAdapter.fillFromBag` already does the same job through `isAssignableFrom`, with the bonus that subclasses (e.g. `McdpModContainer`) match. Replacing it with the strict-equality map would lose flexibility for no real gain. Kept the bag matcher.

# ADR-0028 — Forge cross-mod registration: one idempotent `ensureRegistered`, reached from a reflective `LoadingModList` walk

**Status:** Accepted. Brings the Forge adapter (MC 1.18.x and 1.20.x, shared source) to parity with Fabric and NeoForge on three features that were silently inert on Forge: the ADR-0018/0019 lazy bridge populator, ADR-0010 stdlib promotion, and download progress reporting. Builds on ADR-0027's two-phase container; does not change it.

## Context

Three gaps, all rooted in the same structural fact: the Forge adapter did every piece of per-mod work inside `McdpModContainer`'s constructor, one mod at a time, with no place for anything cross-mod and no entry point reachable before FML's `loadMod` sweep.

1. **The mixin bridge was broken on Forge.** `McdpProvider.installLazyPopulator(Runnable)` had zero callers in `forge-1.18/`. It exists because MC's `Main.main()` runs `Bootstrap.bootStrap()` *before* `ServerModLoader.load()`: a mixin rewritten by the `mcdp-bridges` plugin resolves its `LOGIC_*` field in `<clinit>`, so a mixin applied to a class touched during bootstrap reaches `McdpProvider.resolveAutoBridgeImpl` against an empty registry and throws `no auto-bridge registered`. Forge has the same ordering, so ADR-0018/0019 did not work there at all.
2. **ADR-0010 did not apply on Forge.** The constructor called `CONSUMER.resolveAll(manifest)` per mod and passed `McdpModContainer.class.getClassLoader()` as `libParent`, so two Scala mods each got their own `scala3-library_3`. `StdlibPromotion.selectPromotions` needs the *union* of all manifests, which a per-mod constructor cannot see.
3. **Library downloads were silent.** Forge called the no-listener `resolveAll` overload; Fabric and NeoForge pass a `LoggingProgressListener`. A first boot fetching hundreds of MB printed nothing.

The constraint that shapes the answer is classpath reach. `net.minecraftforge.fml.loading.LoadingModList` — NeoForge's route to "every mod FML knows about" — lives in the `fmlloader` artifact. Verified by `unzip -l` against the local artifact cache: the class is absent from `fmlcore-1.18.2-40.3.12`, `fmlcore-1.20.1-47.4.20`, `forgespi-4.0.11` and `forgespi-7.1.6`, and `fmlloader` is on neither band's compile classpath. The same is true of `net.minecraftforge.fml.loading.progress.ProgressMeter` (ADR-0027 item 3).

Note also that Forge's `IModInfo` has **no** `getLoader()` — that is a NeoForge addition (`javap` against forgespi 4.0.11 and 7.1.6), so NeoForge's `LANGUAGE_ID.equals(info.getLoader().name())` filter does not port verbatim.

## Decision

**Move all per-mod registration out of `McdpModContainer`'s constructor into a single static, idempotent `McdpLanguageProvider.ensureRegistered(IModInfo)`**, cached by mod ID, returning `(ModClassLoader, Manifest)`. It reads the manifest, resolves libraries, applies the promotion selection, builds the `ModClassLoader`, calls `McdpProvider.registerMod` and registers the bridge manifest. This mirrors the NeoForge adapter's shape exactly.

`ensureRegistered` has two callers:

- `McdpModContainer`'s constructor, during FML's `loadMod` sweep (the normal path); and
- the **lazy populator**, installed into `McdpProvider` from `McdpLanguageProvider`'s static initializer, which fires on the first auto-bridge registry miss — i.e. from a mixin `<clinit>` during `Bootstrap.bootStrap()`, before `loadMod`. The `REGISTERED` cache makes the later `loadMod` pass a no-op for mods the populator already handled.

**Reach `LoadingModList` reflectively.** `Class.forName("net.minecraftforge.fml.loading.LoadingModList")` against this class's loader, falling back to the thread context loader, then `get()` / `getMods()`. The *elements* are plain `IModInfo`, which is a compile-time type from forgespi on both bands, so only the two method handles are reflective. Any failure (`ReflectiveOperationException`, `RuntimeException`, `LinkageError`) logs at `FINE` and yields an empty list.

**Filter mcdp mods by `IModFileInfo.requiredLanguageLoaders()`** — the `LanguageSpec` record, present with identical shape on forgespi 4.0.11 and 7.1.6, carries the `modLoader` key from `mods.toml` — with the presence of `META-INF/mcdepprovider.toml` as a fallback. This replaces NeoForge's `getLoader().name()`, which Forge's `IModInfo` does not have.

**Stdlib promotion runs off that same walk.** `ensurePromotionInitialized()` is called once, under a lock, on the first `ensureRegistered`: it walks every mcdp mod, reads each manifest, runs `StdlibPromotion.selectPromotions`, builds one shared library loader for the winners and caches it as `libParent`. Each mod's own library list then has the promoted coordinates stripped (`stripPromoted` + `filterNonPromoted`), exactly as on NeoForge. If the walk yields nothing, the selection is empty and behaviour is identical to before this ADR.

**Progress reporting is a JUL-backed `ProgressListener` nested in `McdpLanguageProvider`.** The event shape and message text match the Fabric and NeoForge `LoggingProgressListener` copies, including the one-line stderr banner at `started`. It uses `java.util.logging` because that is what the Forge adapter already logs through; introducing slf4j here would be a third logging API in one module. It is deliberately **not** wired to FML's `ProgressMeter`, which is unreachable at compile time on both bands.

`McdpModContainer` keeps only what is genuinely per-container: the entry FQN, the entry `Class` resolved with `initialize = false`, the `IEventBus`, the `CONSTRUCT` `activityMap` entry and `acceptEvent`. ADR-0027's staging is untouched, and `McdpProvider.registerMod` stays eager.

## Consequences

**Positive:**
- The ADR-0018/0019 mixin bridge works on Forge for the first time; mixins that fire during `Bootstrap.bootStrap()` now find a populated registry.
- ADR-0010's cross-mod stdlib unification applies on Forge: N Scala mods share one `scala3-library_3` loader instead of N copies.
- First-boot downloads are visible in the log and on stderr.
- Registration is now reachable without a `ModContainer`, and idempotent, so the order FML happens to use no longer matters.
- Forge, NeoForge and Fabric now have the same registration shape, which makes the three adapters diffable.

**Negative:**
- A reflective dependency on an FML-internal class. If Forge moves or renames `LoadingModList`, the mixin bridge silently reverts to its current broken state and promotion silently degrades to per-mod copies — the failure is a `FINE` log line, not an error. That is the intended trade: a boot that works with worse sharing beats a boot that dies.
- Promotion is decided at the first `ensureRegistered`. A mod that somehow arrives after that (a latecomer not in `LoadingModList`) is registered against the already-built shared loader and may not contribute its version to the selection. NeoForge has the identical window.
- `McdpModContainer` is no longer self-contained; reading it requires reading `McdpLanguageProvider` too.
- The registration statics (`LoaderCoordinator`, `LibraryCache`, promotion state) moved classes, so the coordinator's parent is now `McdpLanguageProvider.class.getClassLoader()`. Same loader in practice — both classes ship in the same jar — but it is a real change.

## Alternatives considered

- **Defer `ModClassLoader` construction to `ModLoadingStage.VALIDATE`, collecting manifests in the constructor and building every loader in one pass at VALIDATE.** This is the design ADR-0027's "free two-pass" invites, and it needs no reflection at all: by VALIDATE every container exists, so the manifest union is available from mcdp's own state. Rejected because it solves only half the problem and breaks the other half. VALIDATE runs *long* after `Bootstrap.bootStrap()`, so the mixin bridge would still be broken — and worse, the lazy populator (which must run during bootstrap) would then have nothing to populate, because no loader would exist yet. It also contradicts ADR-0027's decision to keep `registerMod` eager. Since the bridge fix forces a `LoadingModList` walk regardless, taking the manifest union from the same walk costs nothing extra.
- **Add `fmlloader` as a `compileOnly` dependency on both bands.** Would make the walk a direct call and `ProgressMeter` usable. Rejected for the same reason ADR-0027 rejected it for `FMLEnvironment`: a new per-band dependency on an artifact whose 1.18-vs-1.20 shape we cannot verify from the local cache, for one class we can reach reflectively — and `buildTransitionHandler`'s signature already proves the `progress` package is band-divergent.
- **Detect mcdp mods by `IModFile.getLoaders()`** (the `List<IModLanguageProvider>` on the mod file). Rejected: it is populated during FML's language-matching phase, which is not obviously complete when the populator fires during bootstrap. `requiredLanguageLoaders()` comes straight out of `mods.toml` parsing and exists as soon as the `IModFileInfo` does.
- **Move `LoggingProgressListener` from Fabric/NeoForge into `core` and share one copy.** Tempting, and the three copies are near-identical. Rejected here: the existing two are slf4j-based and package-private per platform, the Forge one is JUL-based, and `core` is deliberately logging-framework-free (that is why `ProgressListener` lives in `deps-lib` as a bare interface). Unifying them means picking a logging API for `core`, which is a bigger decision than this ADR should carry.
- **Give up on the Forge mixin bridge and document it as unsupported.** Rejected: it is a headline feature, the fix is ~40 lines, and the failure mode today is a confusing runtime exception rather than a clean "unsupported".

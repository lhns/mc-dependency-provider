# ADR-0029 — Forge 1.17 runs forgespi 4.0.x, so the band shares the 1.18 adapter source

**Status:** Accepted. Corrects ADR-0023's "forgespi 3.2 (1.17)" premise and retires the last
stub adapter in the tree. ADR-0027's lifecycle staging and ADR-0028's registration design
apply unchanged.

## Context

`forge-1.17/` was the only genuine stub left: its `McdpLanguageProvider.getFileVisitor()`
threw `UnsupportedOperationException`, and `multi-1.17` deliberately left `fmlModType` unset
so FML would never route to it.

The stated reason was an SPI generation gap. The catalog pinned `forge-spi-mc117 = "3.2.3"`,
and forgespi 3.2.3 really is shaped differently from 4.0.x (`javap` against the cached
`forgespi-3.2.3.jar`): `IModLanguageLoader.loadMod` has the arity
`(IModInfo, ClassLoader, ModFileScanData)` rather than 4.0's
`(IModInfo, ModFileScanData, ModuleLayer)`; `IModFileInfo` has neither `getFile()` nor
`requiredLanguageLoaders()`, so the 1.18 adapter's manifest-discovery route does not exist;
and `IModFile.findResource(String)` is single-arg, not varargs.

All of that is true of 3.2.3 — and irrelevant, because **Forge 1.17.1 does not run forgespi
3.2.3.** From the artifacts Forge publishes today:

1. `fmlloader-1.17.1-37.1.2.pom` declares `net.minecraftforge:forgespi:4.0.+` (compile scope).
   (Dates agree: `forgespi-3.2.3.jar` is `Last-Modified: 2021-06-10`, the pre-release snapshot
   SPI; Forge 37.1.2 is from 2022-01.)
2. `javap -c` on `fmlcore-1.17.1-37.1.2`'s `net.minecraftforge.fml.ModLoader` shows the
   dispatch site as `IModLanguageLoader.loadMod:(…/IModInfo;…/ModFileScanData;Ljava/lang/ModuleLayer;)` —
   the 4.0 overload.
3. `javap` on `fmlloader-1.17.1-37.1.2`'s `moddiscovery.ModFileInfo` shows `getFile()` (plus
   the synthetic `IModFile getFile()` bridge, which only exists because the *interface*
   declares it) and `requiredLanguageLoaders()`; `moddiscovery.ModFile.findResource` is the
   varargs form. `ModInfo` and `ModFileInfo` implement exactly forgespi 4.0.11's `IModInfo`
   and `IModFileInfo`, every method.

`fmlcore-1.17.1-37.1.2` also carries the same `ModContainer` the 1.18 design targets —
`protected Supplier<?> contextExtension`, `protected final Map<ModLoadingStage, Runnable>
activityMap`, `protected acceptEvent(T)`, a public `ModContainer(IModInfo)`,
`ModLoadingStage.CONSTRUCT` — and `javap -c` on `buildTransitionHandler` shows the identical
per-stage sequence ADR-0027 verified on 1.18/1.20. Its POM declares `eventbus 5.0.+`, the
line the 1.18 band pins at 5.0.7.

## Decision

**Fix the pin, don't write a second adapter.** `forge-spi-mc117` becomes `4.0.11` (what the
1.18 band uses), and `forge-fmlcore-mc117 = "1.17.1-37.1.2"` joins the catalog.

**`forge-1.17/` shares `forge-1.18/src/main/java` via `sourceSets { main { java.setSrcDirs(…) } }`,**
exactly as `forge-1.20/` already does; its own `src/main/resources` (the
`META-INF/services/…IModLanguageProvider` entry) stays. The stub
`forge-1.17/src/main/java/.../McdpLanguageProvider.java` is deleted.

The band compiles at `options.release = 16` (MC 1.17 ships Java 16), one below the 1.18/1.20
bands. The shared source needs no Java-17 feature. Verified by compiling
`forge-1.18/src/main/java/de/lhns/mcdp/forge/*.java` with `javac --release 16 -Xlint:all`
against `core`/`deps-lib` + `forgespi-4.0.11` + `fmlcore-1.17.1-37.1.2` + `eventbus-5.0.7` +
`asm-9.7.1` + `maven-artifact-3.8.5`: clean, no errors and no warnings.

With a working adapter, `multi-1.17` sets `fmlModType.set("LANGPROVIDER")` like the other
Forge-bundling bands, which is what makes FML route to the service at all.

## Consequences

**Positive:**
- The last stub in the tree is gone; `mcdp-1.17` is a real Forge band.
- One Forge adapter source serves three bands (1.17, 1.18, 1.20). Every future fix to
  `McdpLanguageProvider`/`McdpModContainer` lands on all three at once.
- ADR-0027 and ADR-0028 apply to 1.17 unchanged — including the reflective `LoadingModList`
  walk, equally necessary here: `LoadingModList` is absent from both
  `fmlcore-1.17.1-37.1.2` and `forgespi-4.0.11`.

**Negative:**
- `forge-1.18/` is now the source of truth for three bands and the lowest supported target
  drops to Java 16. A Java-17 feature introduced into that file silently breaks the 1.17
  band's compile. The `release = 16` build is the guard; there is no CI cell for 1.17 yet.
- Like 1.18, the 1.17 band is compile-verified only. Only `forge-example-1.20` boots in CI.
- Forge's own POM declares a `4.0.+` range, so the exact runtime SPI is not pinned by us.
  Everything the adapter calls is present on the concrete `fmlloader-1.17.1-37.1.2` classes,
  so the compile pin at 4.0.11 is an upper bound we know the runtime satisfies.

## Alternatives considered

- **Write a separate 3.2.3-shaped adapter for 1.17**, reflecting onto FML's concrete
  `moddiscovery.ModFileInfo.getFile()` to work around the missing SPI method. Rejected: it
  solves a problem that does not exist. The reflective hop *would* have worked — but only
  because the concrete class is already a 4.0 implementation, which is the whole point.
- **Use the `ClassLoader` argument 3.2's `loadMod` passes** and read the manifest via
  `cl.getResource("META-INF/mcdepprovider.toml")`. Rejected twice over: 3.2 is not the
  runtime SPI, and with two mcdp mods present the route can return the other mod's manifest.
- **Keep `forge-1.17` as its own copy of the source.** Rejected: the files would be
  identical, and a divergence between them would be a bug. Splitting the tree back out is a
  one-line build change if 1.17 ever does need to diverge.

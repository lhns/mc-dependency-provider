# ADR-0024: sharedPackages discipline — build-time validators for cross-classloader granularity

**Status:** Accepted.

## Context

mcdp's per-mod URLClassLoader model (ADR-0001) coexists with Sponge Mixin's game-layer transformer. The seam between the two is `mcdepprovider.sharedPackages`: a list of package prefixes the per-mod loader delegates parent-first instead of child-first, so a class in one of those packages resolves to the same `Class` object on both sides of the loader boundary.

The user's job is to declare the **right granularity** for that list. Two real incidents in late 2026 showed both directions are foot-guns:

### "Share too coarse" — mc-game-of-life-3d

`NoClassDefFoundError: scala/Option` during `RegisterEvent` dispatch. The user had set `sharedPackages.add('…gameoflife3d.block.')`. That package contained both a small bridge-interface trait AND heavy Scala-using block classes. Sharing the package pulled the Scala-using classes onto the platform classloader, which has no `scala.Option` on its URLs.

The user's fix: extract the bridge interface into a sibling `…gameoflife3d.iface` package and share only that.

### "Share too narrow" — mc-blockshifter

`ClassCastException: TrappedChestBlockEntity ... cannot be cast to ... BlockEntityAccessor` from `WorldUtil.scala:28` (`entity.asInstanceOf[BlockEntityAccessor]`). `BlockEntityAccessor` is a `@Mixin(BlockEntity.class)` interface declaring `@Accessor` methods. Mixin merges it into the vanilla `BlockEntity` class on the game-layer transformer. The user's `WorldUtil` runs on the per-mod `ModClassLoader`, and the cast resolves `BlockEntityAccessor` against that loader's URLs, finding a fresh `Class` object — different identity from the merged one. CCE.

The user's fix: `sharedPackages.add('…blockshifter.mixin.')`.

Both fixes are correct and idiomatic. The question this ADR answers: should mcdp do something so the next mod hits a clear build-time error rather than a stack trace at world-load time?

## Decision

**Two complementary build-time validators.** Neither auto-fixes; both produce precise actionable errors. Wired into `:check` so they run as part of standard verification.

- **Validator A (over-share):** for every class IN a `sharedPackages` entry, ASM-walk references and assert each one resolves to a platform-visible package, another `sharedPackages` entry, or the class's own jar. Anything else (Scala stdlib, Kotlin stdlib, mod-private types in non-shared packages) won't load when the class is moved to the platform loader; fail the build.

- **Validator B (under-share):** seeded by a configurable annotation list (`bridges.crossLoaderAnnotations`, default `[Mixin]`). Build the registry of types annotated with one of those annotations; for `@Mixin`, narrow to interfaces declaring `@Accessor`/`@Invoker` methods (those are the only user-visible accessor types). Walk every NON-cross-loader-annotated class in the mod for refs to registry members; if the target's package isn't in `sharedPackages`, fail the build with a generated `sharedPackages.add('<pkg>.')` line.

**Validate, don't auto-fix.** Auto-narrowing or auto-extracting would silently rewrite the user's package tree, producing a different debugging headache when the rewrite chooses wrong. Auto-sharing every `@Mixin` package by default would re-introduce the over-share footgun if a mixin package happens to have neighbours with stdlib-using code. The user's intent stays load-bearing; mcdp's job is to flag the mistake before runtime.

### Why a separate ADR rather than appending to ADR-0018

ADR-0018 (automatic mixin bridge codegen) is about what bytecode mcdp rewrites and what it doesn't. The `sharedPackages` discipline is orthogonal: it's about what classes get loaded by which classloader, and how the user expresses that intent. The two cross-reference (the validators run alongside the bridge codegen and share an ASM pipeline) but they're different decisions with independent rationales.

## The "share too coarse" footgun (validator A)

Mechanism. When a class is in `sharedPackages`, the per-mod `ModClassLoader` delegates parent-first for the class lookup, meaning the class's bytecode comes from the platform/parent loader's URLs. The mod's per-mod-loader URLs (its Maven dep closure: scala-library, kotlin-stdlib, etc.) are not on the platform loader. So if a shared class has a `scala.Option` reference anywhere — descriptor, signature, opcode — the JVM throws `NoClassDefFoundError` the first time that reference is reached.

Validator A catches it at build time by scanning every class in `sharedPackages` for refs to types not on the platform-visible set:

```
mcdepprovider: shared-package class com.example.mod.block.CellBlock references types
not visible to the platform classloader:
  - scala.Option
  - scala.collection.immutable.List
Sharing this class moves it onto the platform/parent classloader, where the mod's
per-mod ModClassLoader URLs are not reachable. The references above will fail with
NoClassDefFoundError at the first static-init or first use of the shared class.
Fix: narrow sharedPackages to a sibling package containing only the bridge type
(e.g. extract the cross-loader-visible interface into its own package), or move
the offending class out of the shared package. See ADR-0024 / docs/bridges.md.
```

Platform-visible set: `BridgePolicy.PLATFORM_PREFIXES` (mirror of `ModClassLoader.PLATFORM_PREFIXES`) plus the user's `sharedPackages` entries plus the codegen's auto-shared bridge package.

## The "share too narrow" footgun (validator B)

Mechanism. Mixin merges `@Mixin`-annotated interfaces' `@Accessor`/`@Invoker` methods into the target class on the game-layer transformer. The merged target class is in the game-layer loader. Mod code casting `(BlockEntityAccessor) someBlockEntity` resolves `BlockEntityAccessor` against the cast site's classloader — the per-mod loader, which has its own `BlockEntityAccessor.class` from the mod jar. JVM Class identity is `(name, defining loader)`, so the per-mod-loader copy and the game-layer copy are distinct types. CCE.

Why mcdp can't auto-rewrite this. `CHECKCAST` and `INSTANCEOF` against type T put T into the calling frame's verifier-level local-variable type. A bridge interface (the rewriter's tool for cross-loader calls) can't change a frame's local-variable type without changing the surrounding bytecode in ways the verifier rejects. Bridge codegen explicitly rejects rewriting these opcodes against mod-private types (see `BridgeMixinScanner.java`); the only fix is to make T resolve to the same `Class` object on both sides — `sharedPackages`.

Validator B catches the missing-share at build time:

```
mcdepprovider: com.example.mod.util.WorldUtil references cross-loader-injected type(s)
whose package is not in sharedPackages:
  - com.example.mod.mixin.BlockEntityAccessor

At runtime, com.example.mod.util.WorldUtil's per-mod ModClassLoader and the
Mixin-merged accessor on the game-layer loader will resolve to two different
Class objects with the same name, producing ClassCastException at the cast site.

Fix: in build.gradle's mcdepprovider {} block, add
  sharedPackages.add('com.example.mod.mixin.')

See ADR-0024 / docs/bridges.md for why CHECKCAST/INSTANCEOF cannot be auto-rewritten.
```

## Configurability — `bridges.crossLoaderAnnotations`

A new `ListProperty<String>` mirroring the existing `bridges.bridgedAnnotations` (ADR-0021):

```kotlin
mcdepprovider {
    bridges {
        // Default: ["org.spongepowered.asm.mixin.Mixin"]
        crossLoaderAnnotations.add("com.example.MyAnnotation")
    }
}
```

Why distinct from `bridgedAnnotations`. The two sets serve different bridge-codegen purposes:

| Annotation | `bridgedAnnotations` | `crossLoaderAnnotations` |
|---|---|---|
| `@Mixin` | yes — Mixin merges body into target | yes — accessor merges into game-layer Class |
| `@EventBusSubscriber` | yes — NeoForge invokes static handler from another thread | no — class isn't merged into another loader |

`@EventBusSubscriber` triggers bridge codegen because its handler runs from a thread whose classloader doesn't hit the per-mod loader's stdlib URLs unless the bridge dispatches through the mod loader. It does NOT inject the class into another loader's namespace, so cross-loader cast identity isn't a concern for it.

Users with custom annotation-driven code-gen frameworks (Architectury `@ExpectPlatform`, custom registrars) override the list to match their tooling.

## Mixin-specific narrowing

For `@Mixin`-annotated classes, validator B further narrows the registry to interfaces with at least one `@Accessor` or `@Invoker` method. Pure target-only mixins (`@Mixin(BlockEntity.class) class FooMixin { @Inject ... }`) merge code into the target but expose no user-visible members; mod code never casts to them, so they don't need to be in any cross-loader registry.

## What mcdp explicitly does NOT do

1. **Auto-share `@Mixin` packages by default.** Re-introduces validator A's footgun if a mixin package has neighbours that use Scala/Kotlin stdlib. The user's `sharedPackages.add(...)` declaration stays load-bearing — explicit intent over magic defaults.

2. **Auto-extract bridge interfaces.** Auto-narrowing would mean rewriting the user's package tree, choosing names ad-hoc. The validator's job is to flag the mistake; the user does the structural fix.

3. **Rewrite `CHECKCAST`/`INSTANCEOF` bytecode.** Verifier-level constraint on the calling frame's classloader; not bridgeable. ADR-0018 documents this; this ADR adds the diagnostic for the missing-share case.

4. **Track cross-mod accessor references** (mod A casts to a Mixin accessor defined in mod B). Multi-module class graph awareness needed; deferred until anyone reports it.

5. **Detect reflective casts at runtime.** `Class.forName("BlockEntityAccessor").cast(...)` escapes the static check. Acceptable — same limitation as any compile-time analysis. Optional future backstop: register-time INFO log when a mod has accessor packages not in `sharedPackages`.

## Cross-references

- ADR-0001 — per-mod URLClassLoaders (the underlying isolation model)
- ADR-0008 — hand-written `@McdpMixin` bridge pattern (the manual escape hatch)
- ADR-0018 — automatic mixin bridge codegen (the related-but-different decision; documents the `CHECKCAST`/`INSTANCEOF` rewrite limit)
- ADR-0021 — generalized bridge codegen with configurable `bridgedAnnotations` (the pattern this ADR mirrors for `crossLoaderAnnotations`)

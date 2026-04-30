# ADR-0021 — Generalized bridge codegen: arbitrary annotations + lambda-site coverage

**Status:** Accepted. Refines [ADR-0008](0008-mixin-via-bridge-pattern.md) and [ADR-0018](0018-automatic-mixin-bridge-codegen.md). Manifest format unchanged from [ADR-0019](0019-bridge-manifest-format-and-registration.md).

## Context

After ADR-0018 shipped, two leaks remained in mcdp's per-mod isolation, both surfacing on real third-party mods:

1. **Mixin-merged Scala lambdas (the fluidphysics CNF).** A mixin body containing a Scala-emitted closure compiles to `INVOKEDYNAMIC LambdaMetafactory` plus a synthetic `lambda$...$N` method on the mixin class. ADR-0018's rewriter had no `INVOKEDYNAMIC` branch, so the indy and synthetic both passed through unchanged. After Sponge merges the mixin into MC's target class, the synthetic body lives on MC's class. JVM resolves the synthetic body's class names via MC's defining loader (FML's `ModuleClassLoader`) — which can't see Scala. Crash: `ClassNotFoundException: scala.math.Ordering` mid-chunk-gen, no fluidphysics frame on the stack.

2. **`@EventBusSubscriber` and other annotation-driven side-loads.** NeoForge's automatic-subscriber registrar reads class-level annotations via ASM and calls `Class.forName(fqn)` against FML's loader. The defining loader of the subscriber becomes FML's, and any Scala/Kotlin/mod-private reference inside its body resolves via FML — which can't see them. Latent leak class for any non-mixin Scala mod; not yet observed in the wild but architecturally identical to leak (1).

Both share a root cause: **a class FML defines contains bytecode references to mod-private/Scala types**. The ADR-0018 bridge pattern fixes this by routing references through a bridge interface whose impl is loaded by `ModClassLoader`. The change in this ADR is in *which* classes the codegen scans (no longer mixin-only) and *which* opcodes it understands (no longer indy-blind).

The user-asked design ("make the bridged annotations configurable") subsumes the alternative "patch FML's registrar at runtime via a Sponge mixin into NeoForge" cleanly: instead of relying on a runtime hook against an evolving NeoForge internal, we transform the consumer class at build time. One mechanism, no version fragility.

## Decision

Two orthogonal extensions to the ADR-0018 codegen:

1. **Generalized seeding.** A new `bridgedAnnotations` `ListProperty<String>` on `BridgeCodegenExtension` lists class-level annotation FQNs to seed on, in addition to the existing `*.mixins.json` discovery. Defaults: `org.spongepowered.asm.mixin.Mixin` and `net.neoforged.bus.api.EventBusSubscriber`. The two seed paths union by FQN; the rest of the pipeline (scanner / rewriter / emitters / manifest) is annotation-agnostic and processes seeded classes uniformly.

2. **Lambda-site coverage.** `BridgeMixinScanner` gains an `INVOKEDYNAMIC` branch that recognizes `LambdaMetafactory.metafactory` and `altMetafactory` bootstrap methods, recursively scans the synthetic body for cross-classloader references, and records a `LambdaSite` per indy. A new `LambdaWrapperEmitter` produces a per-site bridge interface + impl pair: the impl's `make(captures...) -> SAM` factory method uses its own `INVOKEDYNAMIC LambdaMetafactory` against an *embedded* synthetic on the impl class. The rewriter replaces the original indy with `spill captures + GETSTATIC LAMBDA_<simple>_<n> + reload captures + INVOKEINTERFACE bridge.make`.

The `LAMBDA_*` static fields and their `<clinit>` initialization use the same `McdpProvider.resolveAutoBridgeImpl` plumbing as ADR-0018 (no runtime API changes). The manifest schema (`META-INF/mcdp-bridges.toml`) is unchanged: lambda sites emit `[[bridge]]` entries with `mixin = container_fqn, field = LAMBDA_*, interface = ..., impl = ...` — the runtime can't tell them apart from regular bridges and doesn't need to.

### Why bytecode rewriting and not a runtime hook

The user's intuitive question — "can't we mixin into FML's `@EventBusSubscriber` registrar and redirect `Class.forName` to our `ModClassLoader`?" — works for leak (2) but not for leak (1). The JVM rule is:

> Class references in bytecode are resolved using the **defining loader of the class containing that bytecode**.

After Sponge merges a mixin's synthetic into MC's target class, the synthetic's bytecode is *part of MC's class*. JVM resolves its class names via MC's defining loader (FML's). Redirecting how the *original* mixin source class was loaded doesn't change anything — the merged code's loader has nothing to do with the mixin source class anymore.

The only way to make MC's defining loader successfully resolve a symbol from a merged mixin body is to ensure that body never names a mod-private/Scala class directly. That's the bridge pattern. Lambda wrappers extend it to the case where the reference lives inside a synthetic that gets merged along with the mixin body.

For symmetry, leak (2) (subscriber side-load) could be fixed by either runtime hook or build-time codegen. We chose codegen because: (a) the same mechanism already covers leak (1); (b) no NeoForge version-fragility (the registrar's class/method shape can rename freely without affecting us); (c) one set of docs.

### Why a separate bridge per lambda site, not a single shared one

Each lambda site has its own capture-type tuple and SAM. Fold them into a single shared bridge interface and the interface descriptor would have to be either erased to `Object` (loses type information) or per-call-site (which is what we already have). The existing `BridgeMember` dedup keys on `(target, kind, name, descriptor)`, which doesn't naturally extend to "this specific indy site at this specific instruction offset". Per-site bridges keep the data model orthogonal to the existing bridges.

### The `*.mixins.json` seed is dropped (post-cleanup)

Initial v1 kept JSON seeding alongside the annotation seed as belt-and-braces against possible Loom-mixin-AP retention stripping. In the cleanup pass after fluidphysics shipped, we removed it: the `@Mixin` annotation has `@Retention(RUNTIME)` per Sponge's contract, the annotation seed catches every mixin in standard configurations, and the JSON parser (plus its `MixinJson` mini-parser) was redundant code surface. If a real-world Loom config ever surfaces retention-stripping behavior, the JSON path can be re-introduced from git history (see follow-up cleanup commit).

## Design

### Annotation-seed scanner

`AnnotationSeedScanner` walks every `.class` under `main.output.classesDirs`, reads the class-level annotation table with `ClassReader.SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES`, and returns the dotted FQN set whose annotations intersect the configured list. Per-class cost: one ASM pass over the class header (no method body parsing).

### Lambda-site detection

`BridgeMixinScanner.handleIndy` detects `INVOKEDYNAMIC` on `LambdaMetafactory.metafactory` / `altMetafactory`, extracts the implementation method handle (bsm arg index 1, identical position in both bsm shapes), and recursively scans the synthetic body via the regular `walkMethod` pass. Cross-classloader references inside the synthetic land in the regular `targets` map, so the existing rewriter routes them through bridges. The site is also recorded in `MixinScanResult.lambdaSites` for the rewriter to know which indys to replace.

Per-class state lives on a `SiteContext`: a stable site index across the whole class (used in the per-site wrapper class name) and a recursion-guard set against re-walking a synthetic that's referenced from multiple indys.

Method-reference indys (impl handle's owner is *not* the container) are not handled and emit a warning — they're rare in mixin bodies and a method ref to a mod-private type would already be flagged by the regular `BridgePolicy.needsBridge` warning path on the underlying call.

### Lambda wrapper emission

For each site, `LambdaWrapperEmitter` produces:

- **Bridge interface** at `${bridgePackage}.${ContainerSimple}$Lambda<n>Bridge` — single abstract method `make(captures...) -> SAM`.
- **Bridge impl** at `${bridgePackage}.${ContainerSimple}$Lambda<n>BridgeImpl` — implements the interface; `make` body does its own `INVOKEDYNAMIC LambdaMetafactory` pointing at an *embedded* private-static synthetic. The synthetic is a verbatim copy of the original (same name, same desc). Because the impl class is loaded by `ModClassLoader` at runtime (per ADR-0019 manifest registration), the embedded synthetic's mod-private references resolve correctly.

### Rewriter integration

`MixinRewriter.rewriteLambdaIndy` replaces each site's indy with a stack-juggle (spill captures, push the per-site `LAMBDA_*` static field, reload captures, INVOKEINTERFACE bridge.make). The `LAMBDA_*` field is added to the container alongside the existing `LOGIC_*` fields and initialized in the same merged `<clinit>` block via `McdpProvider.resolveAutoBridgeImpl`.

### Configuration

```kotlin
mcdepprovider {
    bridges {
        bridgedAnnotations.set(listOf(
            "org.spongepowered.asm.mixin.Mixin",
            "net.neoforged.bus.api.EventBusSubscriber",
        ))
    }
}
```

Default covers Sponge-Mixin and NeoForge 1.21.x's automatic event subscriber registrar. Override per-project to pin to a different NeoForge version's annotation FQN or to add custom annotation-driven side-loads.

## Consequences

**Positive:**

- **fluidphysics CNF closes.** The mixin-merged Scala lambda case has a build-time fix that respects per-mod isolation.
- **`@EventBusSubscriber` works automatically.** No mod author action needed for subscribers that touch Scala/Kotlin.
- **One mechanism, one set of docs.** Both leaks share the bridge codegen pipeline; the ADR-0018 docs extend by one section instead of fragmenting.
- **Future-proof against new annotation-driven side-loads.** If a mod (or NeoForge subsystem) adds another scanner, just add the annotation FQN to `bridgedAnnotations`.
- **No NeoForge version dependency.** No mixin against `net.neoforged.fml.javafmlmod.AutomaticEventSubscriber`, no reflection against modlauncher internals.

**Negative:**

- **More codegen output per lambda site.** Each site adds two `.class` files (bridge interface + impl) to the mod jar. Negligible per site (a few hundred bytes); a real-world mod with dozens of lambdas could add ~50 KB.
- **Capture type-fidelity at the bridge boundary.** v1 keeps capture types as-declared in the bridge interface descriptor. A Scala-typed capture would put a Scala name in a class file loaded by FML — same limitation as ADR-0018's signature warnings. The fluidphysics-style cases (closures over Java/MC types passing Scala values inside the body) work; pure Scala-capture cases warn.
- **Dead synthetic methods on rewritten classes.** The original synthetic on the container is left as dead code (no caller after rewrite). Sponge tolerates dead synthetics; the bytes-on-disk cost is small.
- **One more thing to break under future Sponge / `LambdaMetafactory` changes.** A new bsm shape (Scala 3 macros emitting hand-rolled MHs, etc.) would be uncovered. Tier-2 CI on a real mod jar is the right place to catch this.

## Affected files

| Path | Change |
|---|---|
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/BridgeCodegenExtension.java` | Added `getBridgedAnnotations()`. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/McdpProviderPlugin.java` | Convention default for `bridgedAnnotations` (covers Mixin + EventBusSubscriber). |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/AnnotationSeedScanner.java` | NEW — ASM walk to seed by class-level annotation. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/LambdaSite.java` | NEW — per-indy site descriptor. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/LambdaWrapperEmitter.java` | NEW — emits per-site bridge interface + impl with embedded synthetic. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/BridgeCodegenTask.java` | Wires annotation seed; emits lambda artifacts; lambda manifest entries. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/BridgeMixinScanner.java` | INVOKEDYNAMIC branch + recursive synthetic walk + LambdaSite emission. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/MixinScanResult.java` | Added `lambdaSites()`. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/MixinRewriter.java` | Indy replacement + `LAMBDA_*` field/clinit injection. |
| `gradle-plugin/src/test/java/de/lhns/mcdp/gradle/mixinbridges/AnnotationSeedScannerTest.java` | NEW — 10 cases. |
| `gradle-plugin/src/test/java/de/lhns/mcdp/gradle/mixinbridges/BridgeMixinScannerLambdaTest.java` | NEW — 7 cases (metafactory/altMetafactory, recursive scan, method ref warning, site indexing, capture types). |
| `gradle-plugin/src/test/java/de/lhns/mcdp/gradle/mixinbridges/LambdaWrapperEmitterTest.java` | NEW — 5 cases (interface shape, impl shape, indy in make body, field naming, capture descriptors). |
| `docs/mixin-bridge.md` | Annotation-driven seeding section + lambda-capture section. |
| `README.md` | "Mods with Mixins" → "Mods with Mixins or annotation-driven side-loads". |
| `docs/pitfalls.md` | Two new entries (mixin lambda CNF, subscriber side-load). |

## Alternatives considered

- **Mixin into `cpw.mods.cl.ModuleClassLoader`** — rejected. ModuleClassLoader is BOOT/SERVICE-layer; loaded by `BootstrapLauncher` before Sponge Mixin initializes. Not a valid `@Mixin` target.
- **Mixin into NeoForge's `AutomaticEventSubscriber` registrar** — rejected. Fixes leak (2) only; doesn't address leak (1)'s lambda-merged case (different JVM resolution rule). Adds NeoForge-version fragility (registrar class/method names can rename across minors).
- **`ITransformationService` redirecting class loads** — rejected. Same reasoning; redirecting subscriber loading doesn't affect MC's class's defining loader's resolution of merged-in synthetic bodies. Promoting Scala stdlib to FML's loader (mentioned in ADR-0009 as a related rejected alternative) breaks per-mod isolation.
- **Build-time output split into `fml-visible/` + `mcdp-private/`** — rejected. Heavy build-system changes; doesn't fix leak (1) either, because the synthetic still gets merged into MC.
- **Move synthetic body to a method on a helper class on `ModClassLoader` and have the wrapper call it** — equivalent to the chosen design but adds an extra indirection without runtime advantage.
- **Inline the synthetic body directly into a wrapper class implementing the SAM** — would require local-variable remapping and runs into "wrapper's defining loader must see Scala" problems if the wrapper is in a shared package. Chosen design instead routes through the bridge interface like every other ADR-0018 bridge, reusing ADR-0019's manifest-registration plumbing.

## Out of scope (revisit conditions)

- **`MethodHandle.invokeExact` / hand-rolled MethodHandle patterns** — some Scala 3 macros emit these instead of `LambdaMetafactory`. Not seen in the wild against mcdp; iterate on scanner if a real mod surfaces it.
- **`Class.forName(modPrivateString)` inside a subscriber/synthetic body** — same limit as ADR-0018 §"CHECKCAST/INSTANCEOF/ANEWARRAY of mod-private types". Scanner emits a warning; user refactors or `sharedPackages.add(...)`.
- **`IEventBus.register(Object)` runtime registration** — bypasses annotation scan. Document as a manual-bridge case (write the subscriber in Java, route Scala calls through a hand-written bridge).
- **Capture type erasure to `Object`** — would let Scala-typed captures flow through bridges. Out of scope for v1; revisit if real mods need it.
- **Mixin into NeoForge for residual annotation-driven loads** — if a NeoForge subsystem adds a new annotation scanner mcdp doesn't yet seed on, the annotation can be added to `bridgedAnnotations` config without code changes. Only revisit if scanning is impossible (e.g., the registered class's bytecode isn't available at build time).

## Errata

### Bridge impl package must not be in `sharedPackages`

**Symptom.** Real-world Scala mod (mc-fluid-physics) crashed during chunk
generation with `ClassNotFoundException: scala.math.Ordering`. The stack
showed `SpringBlockFeatureBridgeImpl.generate(Unknown Source)` invoking
`SpringBlockFeature.generate(SpringBlockFeature.scala)` (a mod-private Scala
class), with `cpw.mods.cl.ModuleClassLoader` as the loader resolving Scala
stdlib.

**Root cause.** ADR-0018 / ADR-0019 emit bridge interfaces *and* impls in
the same package and auto-add that package to `sharedPackages`. The
sharedPackages prefix triggers parent-first delegation in `ModClassLoader`:
when `McdpProvider.resolveAutoBridgeImpl` does
`Class.forName(implFqn, true, modClassLoader)`, the load goes parent-first
to FML's loader, which finds the impl on the mod jar's modulepath and
defines it. The impl is now defined by FML. JVM resolves class names in the
impl's bytecode (the target Scala class, then transitively Scala stdlib)
via the impl's defining loader — FML's. FML can't see Scala. Crash.

The architectural reason this didn't surface earlier: existing test mods
(`mixin-example`) routed bridge calls into Java helpers, not Scala
directly, so the impl bodies never named anything FML couldn't resolve.

**Fix.** Emit impls in a sibling package — `<bridgePackage>_impl` — that
does NOT match the auto-shared `<bridgePackage>.` prefix (because of the
trailing dot). `ModClassLoader.loadClass` then child-loads impl FQNs:
`findClass` succeeds locally (the codegen output is on the mod's URL list
via `expandDevRoots`), defines the impl on `ModClassLoader`. Impl's body
resolves Scala via `ModClassLoader`'s parent chain → libsLoader → ✓.

The bridge interface stays in `<bridgePackage>` (auto-shared) so MC's
merged-in mixin code resolves the interface to the same `Class` on both
loaders.

**Affected files.** `BridgeImplEmitter.java`, `LambdaWrapperEmitter.java`,
`BridgeCodegenTask.java` (extra `Files.createDirectories` for the new impl
directory).

**Verified** with mc-fluid-physics neoforge runServer: boots clean through
`"Done (16.525s)! For help, type \"help\""` — chunk generation completes
without the previous CNF.

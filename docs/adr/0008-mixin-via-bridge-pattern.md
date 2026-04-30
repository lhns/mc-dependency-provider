# ADR-0008 — Mixin supported via a bridge pattern

**Status:** Accepted — refined by [ADR-0018](0018-automatic-mixin-bridge-codegen.md) and [ADR-0021](0021-generalized-bridge-codegen.md). The bridge-pattern runtime architecture in this ADR is unchanged; ADR-0018 layers a Gradle-side bytecode-rewriter on top so the mod author writes a normal Sponge-Common-style mixin and the bridge plumbing is generated for them, and ADR-0021 generalizes the rewriter to seed on arbitrary class-level annotations (so `@EventBusSubscriber` and similar FML side-loads are auto-bridged) and to rewrite `INVOKEDYNAMIC LambdaMetafactory` sites whose synthetic bodies leak Scala references through Sponge's mixin merge.
**Supersedes:** the earlier draft "ADR-0008 — Mixin unsupported for Scala mod classes"

## Context

[Mixin](https://github.com/SpongePowered/Mixin) — in the form of the FabricMC fork `net.fabricmc:sponge-mixin:0.16.5+mixin.0.8.7`, which both modern NeoForge (via FancyModLoader's `loader/build.gradle`) and Fabric Loader 1.21.1 ship — is the dominant bytecode-patching mechanism for modifying Minecraft internals. Historical guidance for this project was that Mixin would not apply to mod classes loaded through per-mod `ModClassLoader`s, so Scala/Kotlin/Java mods using mc-lib-provider couldn't use Mixin.

That was too strong. Mixin actually works in two stages:

1. **Read** the mixin class's bytecode (via `IMixinService.getBytes(className)`) at startup, extract `@Inject` / `@Redirect` / `@Shadow` metadata, discard.
2. **Transform** the target class (typically `net.minecraft.*`) at load time, inlining code from the mixin class into the target. The target is loaded by NeoForge's `TransformingClassLoader` — that loader's Mixin hook is what matters, not where the mixin class lived.

The real constraint is: **anything the inlined mixin body references must be resolvable from the target's defining classloader** (NeoForge's). A call to `MyScalaObject.foo()` inlined into `FlowingFluid.canSpreadTo()` dispatches through NeoForge's loader, which doesn't see Scala stdlib or the mod's Scala classes — they live in the per-mod `ModClassLoader`.

So the question is: *how does mixin code reach into a Scala/Kotlin mod's impl without making that mod's classes visible on NeoForge's loader?*

## Decision

Use a **Java-interface + implementation bridge pattern**. The mixin class is Java and references only a Java interface. The Scala (or Kotlin) implementation of the interface is loaded through the per-mod classloader. JVM interface dispatch naturally routes through the *receiver's* defining loader, which is where the Scala impl lives.

Two supporting pieces in `core/`:

- **`@McLibMixin(impl = "fully.qualified.ImplClassName")`** — annotation placed on the mixin class. Declares which impl class to instantiate, resolved through the mod's per-mod classloader.
- **`McLibProvider.loadMixinImpl(Class<I> iface)`** — static helper that walks the stack to find the calling mixin class, reads its `@McLibMixin`, looks up the mod's `ModClassLoader`, loads the impl, casts to `I`, returns. Result is cached per mixin class.

### Owner resolution — three paths, checked in order

`loadMixinImpl` must map the calling mixin class to the right `ModClassLoader`. The resolver tries three strategies in order:

1. **Annotation `modId` (recommended).** If the mixin's `@McLibMixin(modId = "...")` is set, use it. **This is the recommended form** — Mixin merges the annotated method body into the target class (e.g. `MinecraftServer`), and when Mixin rewrites the `<clinit>` static-field init the runtime caller on the JVM stack is the target class, not the mixin. The stack walk in paths 2–3 may then miss. Setting `modId` explicitly sidesteps the ambiguity.
2. **Pre-registered FQN map.** Platform adapters can call `McLibProvider.registerMixinOwner(fqn, modId)` during boot (typically after reading each mod's Mixin config) so the resolver can look up a mixin class's owning mod without a stack walk. Useful when the mod author didn't set `modId` but the adapter knows the mapping.
3. **Stack walk + single-mod fallback.** Last resort: walk frames to find a class outside `McLibProvider` that carries `@McLibMixin`. If none is found and there's exactly one mod registered, that mod is assumed the owner. This is enough for single-mod dev boots; it's **not** enough under multi-mod configurations, which is why 1 and 2 exist.

Mod authors should prefer path 1. Platform adapters may implement path 2 as a defense-in-depth fix for mods that forget to set `modId`.

### Worked example

```java
// In the mod's Java source (lives in the mod jar, loaded by NeoForge's loader)
package de.lolhens.fluidphysics.api;

public interface FlowableFluidLogic {
    void canSpreadTo(BlockGetter bg, BlockPos fluidPos, BlockState fluidBs,
                     Direction dir, BlockPos flowTo, BlockState flowToBs,
                     FluidState fluidState, Fluid updatedFluid,
                     CallbackInfoReturnable<Boolean> info);
    // ... one method per @Inject callback
}
```

```java
// The mixin class — Java, no Scala references
@Mixin(FlowingFluid.class)
@McLibMixin(impl = "de.lolhens.fluidphysics.mixin.FlowableFluidLogicScala")
public abstract class FlowableFluidMixin {
    private static final FlowableFluidLogic logic =
        McLibProvider.loadMixinImpl(FlowableFluidLogic.class);

    @Inject(at = @At("HEAD"), method = "canSpreadTo", cancellable = true)
    protected void fp$canSpreadTo(BlockGetter bg, BlockPos fluidPos, /*...*/,
                                   CallbackInfoReturnable<Boolean> info) {
        logic.canSpreadTo(bg, fluidPos, /*...*/, info);
    }
}
```

```scala
// Scala impl — per-mod classloader, free to use cats / circe / Scala stdlib
package de.lolhens.fluidphysics.mixin

class FlowableFluidLogicScala extends FlowableFluidLogic {
    def canSpreadTo(bg: BlockGetter, fluidPos: BlockPos, /*...*/,
                    info: CallbackInfoReturnable[java.lang.Boolean]): Unit = {
        val fluid = fluidState.getType
        if (flowDirection == Direction.DOWN && /* Scala-heavy logic */)
            info.setReturnValue(true)
    }
}
```

### Runtime dispatch walkthrough

1. `FlowingFluid` is loaded by NeoForge's `TransformingClassLoader`. Mixin's `ILaunchPluginService` hooks the load and inlines code from `FlowableFluidMixin` into `FlowingFluid.canSpreadTo`.
2. Inlined code reads the static field `logic`. Its declared type is `FlowableFluidLogic` — a Java interface. `FlowableFluidLogic.class` is in the mod jar, which is on NeoForge's game layer, so NeoForge's loader resolves the interface.
3. The `logic` field was initialized by `McLibProvider.loadMixinImpl(...)`. That helper found `@McLibMixin(impl = "...")` on the mixin class, located the mod's `ModClassLoader`, loaded `FlowableFluidLogicScala` through it, instantiated, and cached the result.
4. `logic.canSpreadTo(...)` is an `invokeinterface`. JVM dispatches by looking at the *receiver's* class — `FlowableFluidLogicScala`, defined by the per-mod classloader. The method body runs in that loader's context, where Scala stdlib and cats and anything else the mod needs are visible.
5. The method returns; control returns to Minecraft code.

No special thread-context trickery, no bytecode rewriting on the mixin class, no global Scala stdlib.

### Shared-package-prefix delegation (the subtle prerequisite)

For this to work, `FlowableFluidLogic` (the interface) must be the *same `Class` object* in both NeoForge's loader and the per-mod `ModClassLoader`. Otherwise the per-mod-loaded impl can't be cast to an interface type from NeoForge's loader.

ADR-0001 updated to include this: the per-mod `ModClassLoader` **parent-firsts** classes under any package prefix the mod declares in its manifest's `shared_packages` list. The convention is that the mod puts interfaces + mixin classes under an `api`-ish subpackage:

```toml
lang = "scala"
shared_packages = ["de.lolhens.fluidphysics.api"]
```

The per-mod loader sees `de.lolhens.fluidphysics.api.FlowableFluidLogic` in its parent-first list, delegates to NeoForge's loader, gets back the same `Class` NeoForge already has. Scala impls (under `de.lolhens.fluidphysics.mixin.*`) stay child-first — per-mod owned.

## Consequences

**Positive:**
- **Mixin works** for Scala/Kotlin/Java mods using mc-lib-provider. No hard constraint preventing authors from using the mainstream bytecode-patching toolkit.
- **Per-mod isolation preserved.** Scala stdlib stays in per-mod loaders; the mod's heavy deps (cats, circe) stay private. Version drift between mods doesn't propagate.
- **No bytecode rewriting.** ADR-0002's unnamed-module trick still handles cats-kernel; we don't resurrect ASM pipelines.
- **No stubber needed.** Mixin classes reference only Java interfaces and Minecraft types, which the Mixin AP handles without issue. The `java-mixin-stubber` workflow (which lets Java mixins reference Scala classes directly) is orthogonal and not required under this design.
- **Cross-loader:** same pattern works on Fabric Loader — interface definition + per-mod impl + Mixin's `sponge-mixin` picks up the mixin just the same.

**Negative:**
- **Boilerplate.** Every `@Inject` needs a matching interface method and a trampoline call. Scales linearly with mixin complexity. For a mod with 20 @Inject points, that's ~20 interface methods + ~20 trampoline methods on top of the Scala impl. Not enormous but real.
- **Signature constraint.** The bridge interface's method signatures must use types resolvable from NeoForge's loader: JDK types, Minecraft types, Spongepowered mixin callback types, and the mod's own Java types. Scala collections like `List[T]` cannot appear on the interface.
- **Declarative overhead.** Mod authors must declare `shared_packages` in the manifest and place shared types under those prefixes. Not hard but not invisible; covered in docs/README.md.

## Alternatives considered

- **Require pure-Java mixins with no cross-language calls** (the old ADR-0008 stance). Workable but more restrictive: even a simple config check like `if (FluidPhysicsConfig.enabled)` forces splitting into a Java side-module. The bridge pattern is no worse and unlocks more.
- **Inject Scala stdlib into the game layer** via `ITransformationService.beginScanning`. Scala stdlib has no keyword packages (unlike cats-kernel), so this would work cleanly at the JPMS level — but it only solves references to the stdlib, not to the mod's own Scala classes. Those still need the bridge. And it breaks per-mod stdlib isolation. Rejected for the same reason as SCF's approach: explicit design goal not to special-case stdlibs only for mixin.
- **Automatic bytecode rewriting of mixin classes** (rewrite `invokestatic ScalaObject.foo()` → `invokestatic McLibBridge.callScala(...)` at Mixin-read time). Would let authors write mixins naturally. Rejected: complex, fragile against Mixin internals, returns can't carry Scala types across loaders anyway so the rewrite would still need to erase to `Object` and force casts.

### `java-mixin-stubber` — evaluated and set aside

[`de.lolhens:java-mixin-stubber`](https://github.com/lhns/java-mixin-stubber) is a build-time source-level stripper (~90 LOC, Apache 2.0, JavaParser-based) that lets a mod ship Java mixin classes which reference Scala classes from the same codebase. Used in the FluidPhysics mod.

**What it does:** at `compileJava` time, runs `Stubber.MIXIN.stubDirectory(scalaSourceDir, tempStubDir)` which reads all Java source under `src/main/scala/` (where mixin classes co-live with Scala code for scalac joint compilation), emits Java stubs containing only `@Mixin`-annotated classes, annotated methods, and imports from `java.*` / `org.spongepowered.*` / `net.minecraft.*`. Everything else — Scala imports, non-mixin classes, method bodies — is stripped. The stubs feed into Mixin's annotation processor for refmap generation; the real Java mixin files (with real Scala references) are compiled separately by scalac's joint compilation and shipped in the jar. Stubs are deleted after `compileJava`.

**Why the project existed:** Mixin's AP chokes when processing Java source files that reference Scala-mangled names (`$colon$colon`, `package$either$`, Scala companion object static forwarders, etc.). The stubber gives the AP a Scala-free view so refmap generation succeeds. At runtime, the real mixin classes (with real Scala references) work because SCF's global Scala stdlib + the mod's own Scala classes being visible to Forge/NeoForge's classloader means the references resolve.

**Why mc-lib-provider doesn't need it:** the stubber solves a build-time AP error, but the *runtime* pattern it enables — Java mixins with direct Scala references — requires the mod's Scala classes to be resolvable from NeoForge's `TransformingClassLoader`. That means global Scala stdlib and globally-visible mod-classes, which mc-lib-provider explicitly doesn't do. The bridge pattern in this ADR replaces the runtime half: mixin classes have no Scala references at all, so Mixin's AP has nothing to choke on, so no refmap workaround is needed.

**When the stubber is still a good tool:** for mods that use SCF (or any loader that globally shares Scala + mod classes on the classloader) and want the ergonomic benefit of writing terse Scala references inside Java mixins. Orthogonal to mc-lib-provider. If a mod author migrates a fluidphysics-style codebase to mc-lib-provider, they drop the stubber at the same time they refactor to the bridge pattern.

**Extending it for Kotlin:** the `Stubber` constructor takes filter predicates; a `Stubber.KOTLIN` preset with different import/method filters (tolerating `$Companion`, `$WhenMappings`, `$default` overloads) would be ~10 LOC. Not relevant to this project, but noted in case it helps upstream.

## Revisit conditions

- If the bridge boilerplate becomes a significant friction point in practice, consider generating the interface + trampoline automatically from annotations on the Scala impl. This is a code-generation plugin (Kotlin/Scala annotation processor or a Gradle subplugin), not a runtime change; the runtime architecture stays the same.
- If someone discovers a clean hook in Mixin to register a per-mod classloader for bytecode lookup *and* for inlined-reference resolution (the latter is the hard part), we could skip the bridge pattern and allow direct Scala references. Unlikely but worth watching.

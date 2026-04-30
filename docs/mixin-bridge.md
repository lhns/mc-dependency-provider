# Mixin in mcdp mods

mcdp loads each mod's classes through a per-mod `ModClassLoader`, but Sponge Mixin is hosted by the *game-layer* classloader (`TransformingClassLoader` on NeoForge, `KnotClassLoader` on Fabric). A mixin holding `import com.example.MyMod` compiles fine but throws `NoClassDefFoundError` at runtime, because the game-layer loader cannot see mod-private types. See [ADR-0001](adr/0001-per-mod-urlclassloaders.md) for the underlying isolation model and [ADR-0008](adr/0008-mixin-via-bridge-pattern.md) for the bridge pattern.

mcdp offers two paths across that boundary. Pick one per project.

## Track 1 (recommended): automatic bridge codegen

Write a 100% normal Sponge-Common-style mixin that calls mod-private classes directly. The Gradle plugin scans your compiled mixin bytecode, generates the bridge interface and forwarder, rewrites the mixin's method bodies to dispatch through the bridge, and registers the impl at mod load. Codegen is on by default — there is nothing to add to your build.

```kotlin
mcdepprovider {
    lang.set("scala")
    sharedPackages.add("com.example.api")  // your own shared types

    // mixinBridges {} is implicit; defaults are correct for the typical case.
    // Override only if you need to:
    //   mixinBridges {
    //       enabled.set(false)                                         // opt out (see Track 2)
    //       modPrivatePackages.add("com.example.mymod.*")              // narrow the rewrite scope
    //       bridgePackage.set("com.example.mymod.mcdp_mixin_bridges")  // change the emitted pkg
    //   }
}
```

Worked example. The mixin and the Scala class look exactly like an ordinary Mixin mod:

```scala
// src/main/scala/com/example/mymod/MyMod.scala
package com.example.mymod

object MyMod {
  def shouldCancel(blockId: String): Boolean = blockId.startsWith("minecraft:bedrock")
}
```

```java
// src/main/java/com/example/mymod/mixin/BlockMixin.java
package com.example.mymod.mixin;

import com.example.mymod.MyMod;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Block.class)
public abstract class BlockMixin {
    @Inject(at = @At("HEAD"), method = "isPossibleToRespawnInThis", cancellable = true)
    private void mymod$isPossibleToRespawnInThis(CallbackInfoReturnable<Boolean> ci) {
        if (MyMod.shouldCancel(this.toString())) ci.setReturnValue(false);
    }
}
```

`.java` mixin sources can live under `src/main/scala/` (Scala joint compilation) or `src/main/kotlin/` (Kotlin joint compilation); the codegen finds them in whichever compile-task output dir contains the bytecode.

Build output: a generated `BlockMixin` whose constant pool no longer references `com.example.mymod.MyMod`, plus an emitted bridge package (`com.example.mymod.mcdp_mixin_bridges`) holding `MyModBridge` (interface, game-layer-loaded) and `MyModBridgeImpl` (implementation, per-mod-loaded). At mod load, mcdp reads a single `META-INF/mcdp-mixin-bridges.toml` per mod (one `[[bridge]]` entry per `(mixin, field)` pair — see [ADR-0019](adr/0019-bridge-manifest-format-and-registration.md) for the format) and registers each entry — no mixin classload is performed (Sponge forbids that). The rewriter also injects a `<clinit>` into the mixin: when Sponge's transformer inlines a mixin method into the target and the JVM resolves the first GETSTATIC of `LOGIC_*`, the mixin's `<clinit>` fires, calls back into `McdpProvider.resolveAutoBridgeImpl`, and populates the field with an impl instance loaded through the per-mod `ModClassLoader`. Every subsequent cross-classloader call goes through the cached bridge interface.

Stack-trace fidelity: `LineNumberTable` is preserved by the rewrite, so a NPE inside `LOGIC_MyMod.shouldCancel(...)` reports the original Scala source line. A few synthetic frames inside `mcdp_mixin_bridges` show up in the chain — those are the auto-generated forwarders.

What automatic codegen does **not** handle (these still need manual `sharedPackages`):

- **Interface injection.** A mixin declared `implements MyModInterface` causes Sponge to copy that interface onto the target class — the target lives on the game layer, so `MyModInterface` itself must be resolvable there. Codegen detects this case and fails the build with a clear error pointing at `sharedPackages.add("com.example.api")`.
- **Mod-private superclass on a mixin** (rare; Mixin doesn't reparent the target so this never does anything useful, but the codegen still flags it).
- **`@Unique` fields whose type is mod-private.** Same constraint, same error. Workaround: type the field with the bridge interface itself.
- **Reflection on mod-private classes** (`Class.forName("com.example.mymod.MyMod")` from inside a mixin). The walker emits a build-time warning when it sees a string-literal `Class.forName` matching a non-platform FQN; runtime behavior is undefined, restructure to call through a bridge.

### What codegen cannot bridge (bridge-model limits)

Three opcodes are **inherently unbridgeable** when their operand is a mod-private type — these are not "deferred features", they are a fundamental limitation of the bridge-pattern shape:

- `CHECKCAST T` — the surrounding bytecode that consumes the cast result has a local-variable type of `T` in its stack-frame map, which references the mod-private type directly.
- `INSTANCEOF T` — almost always followed by a CHECKCAST to the same type, with the same locals-typing problem.
- `ANEWARRAY T` — yields a `T[]` whose element type the surrounding code references in its locals.

A bridge interface lives on the game-layer classloader and **cannot mention a mod-private type in any signature**. Any rewrite that produced a bridge call returning `T` would still leave `T` on the operand stack, which the surrounding bytecode references in ways the bridge cannot fix. Class-header rewriting is out of scope (see ADR-0018).

When the scanner sees one of these opcodes, the build emits a warning that points at this section. Two ways out:

1. Add the package to `mcdepprovider.sharedPackages` so `T` is loaded by the game-layer loader (parent-first delegation). Suitable when `T` has no transitive mod-private dependencies that would also need to follow.
2. Restructure the mixin so the cast/check/array-construction lives in mod code, accessed through a bridge method that takes/returns a non-mod-private type (e.g., `Object` or a shared interface).

## Track 2 (advanced): hand-written bridge

For users who want explicit control (no codegen, no bytecode rewriting): keep the original `@McdpMixin` pattern from ADR-0008. Disable codegen with one line and write the interface, impl, and trampolines yourself.

```kotlin
mcdepprovider {
    mixinBridges { enabled.set(false) }
}
```

The shape is documented in [ADR-0008](adr/0008-mixin-via-bridge-pattern.md): a Java bridge interface in a `sharedPackages`-listed package, a Scala/Kotlin impl in any mod-private package, `@McdpMixin(impl = "...", modId = "...")` on the mixin, and `McdpProvider.loadMixinImpl(MyBridge.class)` in the mixin's static init. The hand-written path is more work per call site, but every line of glue is yours and `javap` shows you exactly what runs.

The two paths are mutually exclusive per project: codegen rewrites the entire mixin set, or you opt out entirely. Mixing the two within one project is not supported — narrowing the codegen scope to a subset of mixins would require per-mixin opt-in metadata that the architecture doesn't carry.

## Annotation-driven seeding (`@EventBusSubscriber` and friends)

The codegen walks every compiled `.class` and selects classes whose class-level annotations match a configured FQN list. Default list (set by `McdpProviderPlugin`):

- `org.spongepowered.asm.mixin.Mixin`
- `net.neoforged.bus.api.EventBusSubscriber`

Both Sponge's `@Mixin` and NeoForge's `@EventBusSubscriber` have `@Retention(RUNTIME)` so they remain on the class file post-compile, where the scanner picks them up. `@EventBusSubscriber`-tagged classes that touch Scala/Kotlin/mod-private code are auto-bridged exactly the same way mixins are. No mod-author action needed.

Override the list to pin to a specific NeoForge version (the registrar's annotation FQN can rename across majors) or to add custom annotation-driven side-loads:

```kotlin
mcdepprovider {
    mixinBridges {
        bridgedAnnotations.set(listOf(
            "org.spongepowered.asm.mixin.Mixin",
            "net.neoforged.bus.api.EventBusSubscriber",
            "com.example.api.MyCustomRegistryAnnotation",
        ))
    }
}
```

See [ADR-0021](adr/0021-generalized-bridge-codegen.md).

## Lambda capture in mixin and subscriber bodies

Scala / Kotlin closures inside an instrumented body compile to `INVOKEDYNAMIC LambdaMetafactory`. The implementation method is a synthetic `lambda$...` on the same class, and that synthetic's body still references mod-private/Scala types directly. After Sponge merges the mixin into MC's target, both the indy and the synthetic come along — and the synthetic body's references resolve via MC's defining loader (game-layer), which can't see Scala. Result: `ClassNotFoundException` at first invocation, often deep inside MC code with no mod frame on the stack.

The codegen handles this automatically. For each lambda site, it emits a per-site bridge interface + impl pair (alongside the regular per-target ones) and rewrites the indy to construct the SAM through the bridge. The bridge impl's factory method does its own `LambdaMetafactory` call against an embedded synthetic on the impl class — and because the impl is loaded by `ModClassLoader`, the embedded synthetic's mod-private references resolve correctly.

`javap` of a rewritten mixin will show no `INVOKEDYNAMIC` for closures over mod-private code; it'll show a stack-juggle + `INVOKEINTERFACE bridge.make` instead. The original synthetic on the mixin class is left as dead code (Sponge tolerates).

Limitation: capture types are passed through to the bridge interface descriptor as-declared. If a closure captures a Scala-typed value, the bridge interface's class file names that Scala type — same limit as ADR-0018's general "no mod-private types in bridge signatures" rule. The fluidphysics-style cases (closures over Java/MC types passing Scala values inside the body) work; pure Scala-capture cases warn.

See [ADR-0021](adr/0021-generalized-bridge-codegen.md).

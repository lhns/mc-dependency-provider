# ADR-0018 — Automatic Mixin → mod-private bridge codegen

**Status:** Accepted — first cut shipped post-v0.1. Refines [ADR-0008](0008-mixin-via-bridge-pattern.md): the bridge pattern itself stays — the codegen is a layer that hides it from the consumer.

## Context

ADR-0008 established the runtime architecture for Mixin support: a Java bridge interface in a `sharedPackages` namespace, a Scala/Kotlin impl class, and an `@McdpMixin(impl = "...")` annotation on the mixin so `McdpProvider.loadMixinImpl(...)` can wire the static field. Correct, but ergonomically harsh for ports — ~15-20 mirrored bridge methods per mixin in a real-world codebase, every call site rewritten from `MyMod.foo(...)` to `LOGIC.foo(...)`, and discoverability is a cliff (users hit `NoClassDefFoundError`, dig through ADR-0008, find `loadMixinImpl`, find `sharedPackages`, assemble the recipe themselves).

The bridge must exist at runtime — but it does not need to be hand-written. With ASM the build can synthesize all three artifacts (bridge interface, forwarding impl, manifest) and rewrite the mixin's compiled bytecode to dispatch through the bridge. The user writes a plain Sponge-Common-style mixin; everything else is mechanical.

## Decision

Generate the bridge surface and rewrite the mixin in place at build time. Make it default-on; opt-out lives in `mixinBridges { enabled.set(false) }` for users who want explicit control over their bytecode.

The pipeline runs as `:generateMcdpMixinBridges`, between `compileJava/compileScala/compileKotlin` and the `Jar` task:

1. **Discover mixins.** Read every `*.mixins.json` under `src/main/resources`, parse with `MixinJson`, extract the package + `mixins`/`client`/`server` arrays.
2. **Scan each mixin's bytecode** with `BridgeMixinScanner`:
   - Validate the class header. `interfaces[]` containing a non-platform FQN, a non-platform `superName`, or a field declared with a non-platform type fail the build with an actionable error pointing at `sharedPackages`. The codegen never rewrites class headers — what you wrote is what Sponge sees.
   - Walk every method body, classify each instruction against a default-bridge policy (anything not on the platform-prefixes allowlist or in user `sharedPackages` gets bridged). Supported: `INVOKESTATIC`/`INVOKEVIRTUAL`/`INVOKEINTERFACE`/`GETSTATIC`/`GETFIELD`/`PUTSTATIC`/`PUTFIELD`/LDC-class, plus the `NEW T / DUP / args / INVOKESPECIAL T.<init>` constructor triplet. `CHECKCAST`/`INSTANCEOF`/`ANEWARRAY` of mod-private types and `Class.forName` on a mod-private string are formal bridge-model limits — the bridge surface cannot mention mod-private types in any signature, so the rewrite cannot reach them; the scanner emits a warning pointing at `docs/mixin-bridge.md` for the workarounds (`sharedPackages` or refactor).
3. **Emit one bridge interface per unique target** (`<bridgePackage>.<TargetSimpleName>Bridge`) as bytecode, holding one method per (member-name, descriptor) tuple. Static methods keep their descriptor; virtual methods get a leading receiver parameter; field reads become getter methods.
4. **Emit one forwarding impl per bridge** as bytecode: stateless, public no-arg ctor, every method body is a one-line forward to the original.
5. **Rewrite the mixin's bytecode** (`MixinRewriter`): add a synthetic `LOGIC_<TargetSimpleName>` static field per target, and replace each cross-classloader call site with a stack-juggle sequence (spill args to fresh locals, push the LOGIC field, reload locals, INVOKEINTERFACE the bridge). `LineNumberTable` is preserved by walking the existing `InsnList` rather than rebuilding the method.
6. **Write a manifest** (`META-INF/mcdp-mixin-bridges/<mixinFqn>.txt`) listing each `(bridge, impl, field)` triple for runtime registration.

At runtime, `McdpProvider.registerMod(modId, ModClassLoader)` reads the manifest, instantiates each impl through the per-mod loader (`Class.forName(impl, true, modLoader).newInstance()`), and assigns it to the mixin's `LOGIC_*` field via reflection. The field's declared type is the bridge interface (parent-first via `sharedPackages`), so the JVM verifier is happy; the field's runtime value is from a child loader, but `invokeinterface` against an interface known to the verifier doesn't care which loader defined the impl — that's how cross-classloader interface dispatch works in any JVM.

### Why bytecode rewriting and not source-level

Source-level rewriting would mean a custom annotation processor or a Gradle-Kotlin/Scala compiler plugin per language, plus a parser per language, plus a way to round-trip back to source for compilation — overconstrained, brittle against scalac evolution. ASM acts on the *output* of any JVM-language compiler, so one pipeline handles Java, Scala 2, Scala 3, and Kotlin uniformly. The cost is a mild constraint on what the rewriter understands (instruction-set limited; classes can be rewritten with ASM but methods that emit unusual bytecode shapes from kotlinc may need future work) — but the consumer surface is identical regardless.

### Why the rewrite is method-bodies-only

Rewriting class headers (`interfaces[]`, `superName`, declared field descriptors) would let codegen handle interface injection automatically — but interface injection causes Sponge to copy the interface to the target class, which lives on the game-layer loader, which still needs the interface FQN to be resolvable there. The fix is a `sharedPackages` entry for the interface package, not a bytecode rewrite. Codegen detects the case, fails clearly, and points at the workaround. Keeping the rewriter strictly to method bodies preserves a key invariant: the mixin you wrote and the mixin Sponge sees share an identical class header and an identical annotation set.

### Why default-on with conservative over-bridging

Most users porting from Sponge-Common-style mixins to mcdp don't want to write bridges by hand — they want it to "just work." Default-on means zero `mixinBridges {}` config in the typical case. The opt-out exists for users who specifically want to write bridges manually (e.g., for explicit control, easier `javap` debugging, or as a migration path for existing `@McdpMixin` code).

The detection policy is conservative: bridge anything that's not provably safe (platform-prefix, user sharedPackages, our own bridge package). Common third-party libraries on the game-layer classpath (commons-lang3, etc.) get bridged unnecessarily, paying one extra layer of virtual dispatch. The bridge impl class running under `ModClassLoader` falls back to parent for child-first misses, finds the real class on the game layer, and the call works. Cost: small, well-understood. Benefit: under-bridging crashes at runtime — and runtime crashes inside Sponge's transformer are notoriously hard to attribute back to a specific mixin call site.

### Refmap is preserved

Sponge Mixin's annotation processor runs during javac/scalac and emits a refmap from the unrewritten bytecode. The refmap describes references from the mixin to its *target class* (e.g., `FlowingFluid.tick`) — which the rewriter never touches, only mod-private references get rewritten. So the refmap remains valid against the post-rewrite class file. (A regression test at the integration-test level pins this; the unit-test pipeline has no refmap to compare against.)

## Consequences

**Positive:**

- **Zero-config consumer surface.** A mod author writes a normal Sponge-Common-style mixin, applies the plugin, and the bridge plumbing is invisible. The README's "Mods with Mixins" callout points at one document.
- **Discoverability backstop.** `ModClassLoader` rethrows `NoClassDefFoundError` on a mod-private FQN as a tailored `ClassNotFoundException` whose message names the codegen + the docs. Even users on a stale mcdp version or with `enabled = false` get redirected to the right answer.
- **Two-way street for power users.** The hand-written `@McdpMixin` pattern from ADR-0008 stays — the codegen is a layer, not a replacement.
- **Language-agnostic.** ASM doesn't care about source language; Java/Scala/Kotlin mixins all run through the same pipeline.

**Negative:**

- **Synthetic frames in stack traces.** `LineNumberTable` preservation maps stack-trace lines back to the original source, but the frames themselves include `<bridgePackage>.<Target>BridgeImpl.foo` between the mixin and the real call. Worth documenting; not worth eliminating.
- **More bytecode in the mod jar.** Each mixin gets a `LOGIC_*` static field per target; each unique target gets a bridge interface + impl. Negligible (~1 KB per target in practice), but technically real.
- **One more thing that can break under future ASM/Mixin changes.** If Sponge ships a new `@At` mode or NeoForge ships a new `@WrapOperation`-flavoured annotation that the rewriter doesn't recognize, the rewrite still completes — the annotations are preserved verbatim — but a smoke test is the proof. Tier-2 CI on a real mod jar is the right place to detect this.
- **`CHECKCAST`/`INSTANCEOF`/`ANEWARRAY` of mod-private types remain unbridgeable** — these are not "deferred", they are formal bridge-model limits documented in `docs/mixin-bridge.md`. A bridge interface cannot mention a mod-private type in any signature, and the surrounding bytecode's locals-typing references the type directly. The user's recourse is `sharedPackages.add(...)` or refactoring the operation into mod code.

## Affected files

| Path | Change |
|---|---|
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/MixinBridgesExtension.java` | NEW. DSL for `enabled`, `modPrivatePackages`, `bridgePackage`. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/McdpProviderExtension.java` | Added `getMixinBridges()` + `mixinBridges(Action)` for the nested DSL. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/McdpProviderPlugin.java` | Conventions for the new DSL; `:generateMcdpMixinBridges` registration; jar/processResources/main.output wiring. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/BridgePolicy.java` | NEW. Default-bridge allow/deny logic. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/BridgeMember.java` | NEW. Identity for one bridge entry. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/BridgeMixinScanner.java` | NEW. ASM walk → cross-classloader-ref grouping. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/MixinRewriter.java` | NEW. ASM tree-mode rewrite of method bodies. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/BridgeInterfaceEmitter.java` | NEW. Emits the bridge interface as bytecode. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/BridgeImplEmitter.java` | NEW. Emits the forwarding impl as bytecode. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/BridgeCodegenTask.java` | NEW. Gradle task gluing the pipeline together. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/MixinScanResult.java` | NEW. Scanner output value type. |
| `gradle-plugin/src/main/java/de/lhns/mcdp/gradle/mixinbridges/MixinJson.java` | NEW. Local copy of `MiniJson` so the gradle-plugin module doesn't depend on `core`. |
| `core/src/main/java/de/lhns/mcdp/api/McdpProvider.java` | Added `wireAutoMixinBridges` in `registerMod`: walks the per-mod jar's `META-INF/mcdp-mixin-bridges/*.txt`, instantiates impls, sets static `LOGIC_*` fields. |
| `core/src/main/java/de/lhns/mcdp/core/ModClassLoader.java` | `loadClass` decorates a missing-class error with a tailored message when the FQN looks mod-private (Layer A). |
| `docs/mixin-bridge.md` | NEW. Two-track docs (codegen + hand-written). |
| `README.md` | "Mods with Mixins" callout swapped to lead with codegen. |
| `docs/adr/0008-mixin-via-bridge-pattern.md` | "Refined by ADR-0018" cross-link. |

## Alternatives considered

- **Hand-written `@McdpBridge` annotation processor.** Generates the bridge interface + impl from a Scala-side annotation. Considered for an earlier draft; subsumed by bytecode rewriting (no consumer-side annotations needed).
- **`@McdpMixin` AP that validates the hand-written bridge at compile time.** Useful but optional; not worth the build-tool complexity once codegen is the default.
- **Reflection-based `McdpProvider.invokeStatic("…", args)`.** Migration aid for SCF ports. Long-term API smell; revisit only if codegen leaves users stuck.

## Errata: runtime wiring strategy (post-v0.1.0)

The original wiring at §"At runtime, `McdpProvider.registerMod`…" did `Class.forName(mixinFqn, false, gameLoader)` and assigned the impl to the `LOGIC_*` field via reflection. **This crashes under real Sponge Mixin** with `IllegalClassLoadError: Mixin is defined in <name>.mixins.json and cannot be referenced directly` — Sponge owns mixin-class identity and forbids any direct classload of a class declared in a `mixins.json`. Unit tests passed only because the test harness has no Sponge transformer in the chain.

The fix mirrors the hand-written `@McdpMixin` pattern (ADR-0008), which already works precisely because its `<clinit>` runs as a side-effect of the JVM resolving the inlined GETSTATIC of `LOGIC` — by then Sponge has already accepted the load:

1. **`McdpProvider.registerMod`** populates an `AUTO_BRIDGE_REGISTRY` keyed by `(mixinFqn, fieldName)` — manifest data only, no classload of the mixin.
2. **The rewriter emits `<clinit>`** in the rewritten mixin (per LOGIC field): `LDC mixinFqn / LDC fieldName / INVOKESTATIC McdpProvider.resolveAutoBridgeImpl / CHECKCAST <bridgeInternal> / PUTSTATIC LOGIC_*`. If a user-authored `<clinit>` exists, the new init blocks are prepended; otherwise a fresh `<clinit>` ending in RETURN is synthesized.
3. **`McdpProvider.resolveAutoBridgeImpl`** lazily loads the impl through the per-mod `ModClassLoader` and caches the resolved instance by key.

Manifest format is unchanged. Both the auto-codegen path and the hand-written `@McdpMixin` path now defer impl resolution to the same JVM event.

### Errata: input-dir resolution (post-v0.1.0)

Codegen scans every `SourceSet` output dir (`main.output.classesDirs` — java + scala + kotlin), not just `compileJava`'s. Required for Scala/Kotlin joint compilation: when a `.java` mixin source lives under `src/main/scala/` (or `src/main/kotlin/`), the polyglot compiler writes the bytecode to its own output dir while `compileJava`'s stays empty. The scanner uses first-match in the FileCollection's iteration order, which is insertion order from Gradle's source-set wiring (java, scala, kotlin).

## Out of scope (revisit conditions)

- **Class-header rewriting** — interface injection / mod-private superclass / mod-private `@Unique` field type. Today: explicit `sharedPackages`. Open if real users hit it often.
- **`CHECKCAST` / `INSTANCEOF` / `ANEWARRAY` of mod-private types** — formal bridge-model limit, see Consequences above. Not deferred work.
- **Per-mod fluidphysics-style ports** — out of scope for this ADR; ports happen separately, this codegen is the substrate they'll run on.

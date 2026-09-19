# ADR-0018 — Automatic Mixin → mod-private bridge codegen

**Status:** Accepted — first cut shipped post-v0.1. Refines [ADR-0008](0008-mixin-via-bridge-pattern.md): the bridge pattern itself stays — the codegen is a layer that hides it from the consumer. Further refined by [ADR-0021](0021-generalized-bridge-codegen.md): seeding generalized to arbitrary class-level annotations (covers `@EventBusSubscriber` etc.); INVOKEDYNAMIC LambdaMetafactory sites now rewrite to per-site wrapper bridges, closing the Scala-lambda-merged-into-MC leak (the fluidphysics CNF).

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
6. **Write a manifest** listing each `(bridge, impl, field)` triple for runtime registration. *(As originally shipped: one `META-INF/mcdp-mixin-bridges/<mixinFqn>.txt` per mixin. Superseded by [ADR-0019](0019-bridge-manifest-format-and-registration.md) — a single `META-INF/mcdp-bridges.toml` per mod.)*

At runtime the impl is instantiated through the per-mod loader and assigned to the mixin's `LOGIC_*` field. *(As originally shipped this happened in `McdpProvider.registerMod`, by reflection; that crashes under real Sponge Mixin with `IllegalClassLoadError`. The shipped fix moved resolution into a synthesized `<clinit>` calling `McdpProvider.resolveAutoBridgeImpl(mixinFqn, fieldName)` — see [ADR-0019](0019-bridge-manifest-format-and-registration.md) for the current registration API. Impls are emitted into the sibling `<bridgePackage>_impl` package — see [ADR-0021](0021-generalized-bridge-codegen.md).)* The field's declared type is the bridge interface (parent-first via `sharedPackages`), so the JVM verifier is happy; the field's runtime value is from a child loader, but `invokeinterface` against an interface known to the verifier doesn't care which loader defined the impl — that's how cross-classloader interface dispatch works in any JVM.

### Why bytecode rewriting and not source-level

Source-level rewriting would mean a custom annotation processor or a Gradle-Kotlin/Scala compiler plugin per language, plus a parser per language, plus a way to round-trip back to source for compilation — overconstrained, brittle against scalac evolution. ASM acts on the *output* of any JVM-language compiler, so one pipeline handles Java, Scala 2, Scala 3, and Kotlin uniformly. The cost is a mild constraint on what the rewriter understands (instruction-set limited; classes can be rewritten with ASM but methods that emit unusual bytecode shapes from kotlinc may need future work) — but the consumer surface is identical regardless.

### Why the rewrite is method-bodies-only

Rewriting class headers (`interfaces[]`, `superName`, declared field descriptors) would let codegen handle interface injection automatically — but interface injection causes Sponge to copy the interface to the target class, which lives on the game-layer loader, which still needs the interface FQN to be resolvable there. The fix is a `sharedPackages` entry for the interface package, not a bytecode rewrite. Codegen detects the case, fails clearly, and points at the workaround. Keeping the rewriter strictly to method bodies preserves a key invariant: the mixin you wrote and the mixin Sponge sees share an identical class header and an identical annotation set.

### Why default-on with conservative over-bridging

Most users porting from Sponge-Common-style mixins to mcdp don't want to write bridges by hand — they want it to "just work." Default-on means zero `mixinBridges {}` config in the typical case. The opt-out exists for users who specifically want to write bridges manually (e.g., for explicit control, easier `javap` debugging, or as a migration path for existing `@McdpMixin` code).

The detection policy is conservative: bridge anything that's not provably safe (platform-prefix, user sharedPackages, our own bridge package). Common third-party libraries on the game-layer classpath (commons-lang3, etc.) get bridged unnecessarily, paying one extra layer of virtual dispatch. The bridge impl class running under `ModClassLoader` falls back to parent for child-first misses, finds the real class on the game layer, and the call works. Cost: small, well-understood. Benefit: under-bridging crashes at runtime — and runtime crashes inside Sponge's transformer are notoriously hard to attribute back to a specific mixin call site.

### Refmap is preserved

Sponge Mixin's annotation processor runs during javac/scalac and emits a refmap from the unrewritten bytecode. The refmap describes references from the mixin to its *target class* (e.g., `FlowingFluid.tick`) — which the rewriter never touches, only mod-private references get rewritten. So the refmap remains valid against the post-rewrite class file. (A regression test at the integration-test level pins this; the unit-test pipeline has no refmap to compare against.)

### Codegen input and output placement

Two mechanics that the first cut got wrong and that are load-bearing for polyglot mods:

- **Inputs.** Codegen scans every `SourceSet` output dir (`main.output.classesDirs` — java + scala + kotlin), not just `compileJava`'s: when a `.java` mixin lives under `src/main/scala/` (or `src/main/kotlin/`), the polyglot compiler writes its bytecode to its own output dir while `compileJava`'s stays empty. First match wins, in Gradle's source-set insertion order. Relatedly, `MixinRewriter` and `BridgeImplEmitter` write through `ClasspathAwareClassWriter`, which overrides `getClassLoader()` so ASM's `COMPUTE_FRAMES`/`getCommonSuperClass` resolves against a `URLClassLoader` built from the consumer's `compileClasspath` ∪ its class outputs rather than the gradle-plugin's own loader (which can't see Minecraft types; `mc-fluid-physics` hit this merging `FluidState`/`BlockState`/`Level` frames). Covered by `ClasspathAwareClassWriterTest`.

- **Outputs.** Rewritten mixins are written back **in place** over `build/classes/<lang>/main/...`; only new classes (bridge interface, impl, lambda wrappers) go to the codegen's own `build/mcdp-bridges/classes/`. Layering the rewritten copy as an extra `main.output.dir(...)` failed because FabricLoader and MDG iterate `classesDirs` in declaration order and first-match resolved the *unrewritten* class, so Sponge applied the unrewritten injection body. The rejected alternatives were reordering `classesDirs` (fragile `setFrom` manipulation, and depends on every loader honoring declaration order) and deleting the originals (same write-to-another-task's-output footprint, plus it risks spurious recompiles). In-place mutation is tolerable because the rewriter is idempotent, compile tasks track *source* state for up-to-date checks, and Sponge's refmap remap and Loom's remapping tasks set the precedent.

  In-place rewriting does interact badly with Gradle's up-to-date check, which cost mc-fluid-physics a shipped jar whose mixins called mod-private targets directly (`NoClassDefFoundError: scala/jdk/CollectionConverters$` mid-tick). An incremental `compileScala` rewrites *all* its outputs, wiping the in-place rewrite; the codegen task's recorded input fingerprint then matches the original bytecode again and its outputs are unchanged, so Gradle skips it. Idempotence doesn't help if the task never re-runs. Fix: `getOutputs().upToDateWhen(t -> false)` in `BridgeCodegenTask` — sub-second cost, and a no-op on already-rewritten input. Regression test: `McdpProviderPluginTest.bridgeTaskRerunsAfterUpstreamOverwrite`. If the layered-output design is ever revisited, that line goes away with it.

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
- **`CHECKCAST`/`INSTANCEOF`/`ANEWARRAY` of mod-private types remain unbridgeable** — these are not "deferred", they are formal bridge-model limits documented in `docs/mixin-bridge.md`. A bridge interface cannot mention a mod-private type in any signature, and the surrounding bytecode's locals-typing references the type directly. The user's recourse is `sharedPackages.add(...)` or refactoring the operation into mod code. **The missing-share case is now caught at build time** by the validator described in ADR-0024 (`bridges.crossLoaderAnnotations`), which generates a precise `sharedPackages.add(...)` line based on which Mixin accessor the offending mod-side cast targets.

## Alternatives considered

- **Hand-written `@McdpBridge` annotation processor.** Generates the bridge interface + impl from a Scala-side annotation. Considered for an earlier draft; subsumed by bytecode rewriting (no consumer-side annotations needed).
- **`@McdpMixin` AP that validates the hand-written bridge at compile time.** Useful but optional; not worth the build-tool complexity once codegen is the default.
- **Reflection-based `McdpProvider.invokeStatic("…", args)`.** Migration aid for SCF ports. Long-term API smell; revisit only if codegen leaves users stuck.

## Out of scope (revisit conditions)

- **Class-header rewriting** — interface injection / mod-private superclass / mod-private `@Unique` field type. Today: explicit `sharedPackages`. Open if real users hit it often.
- **`CHECKCAST` / `INSTANCEOF` / `ANEWARRAY` of mod-private types** — formal bridge-model limit, see Consequences above. Not deferred work.
- **Per-mod fluidphysics-style ports** — out of scope for this ADR; ports happen separately, this codegen is the substrate they'll run on.

## Errata

**Runtime wiring and manifest format are superseded by [ADR-0019](0019-bridge-manifest-format-and-registration.md).** The registration shape and on-disk format went through four iterations (URL scan → directory scan → index file → unified TOML) before settling; ADR-0019 has the current format and API. The settled codegen-time corrections (input-dir resolution, ASM `getCommonSuperClass` loader, in-place rewrite, the up-to-date hole) are folded into the Decision above.

### Rename `mcdp_mixin_bridges` → `mcdp_bridges` (post-ADR-0021)

Once ADR-0021 generalized the codegen to seed on arbitrary class-level annotations (subscribers, custom registry-driving annotations, …), the "mixin" prefix in the user-facing names was misleading. A cleanup pass renamed every consumer-visible identifier:

- `META-INF/mcdp-mixin-bridges.toml` → `META-INF/mcdp-bridges.toml`
- Default `bridgePackage` `<group>.<mod>.mcdp_mixin_bridges` → `<group>.<mod>.mcdp_bridges`
- Sibling impl package `<...>_impl` follows
- `mixinBridges { }` DSL → `bridges { }`
- `MixinBridgesExtension` → `BridgeCodegenExtension`
- Task `:generateMcdpMixinBridges` → `:generateMcdpBridges`
- Build dir `build/mcdp-mixin-bridges/` → `build/mcdp-bridges/`
- Doc `docs/mixin-bridge.md` → `docs/bridges.md`

Hard cut, no deprecated alias — pre-v0.1.0, no shipped consumer was depending on the old DSL. The rename table below is the migration guide; the standalone migration doc was retired once the pre-rename snapshots aged out.

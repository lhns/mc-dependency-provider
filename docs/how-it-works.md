# How mcdp works

This is a single end-to-end walkthrough of what mcdp actually does, from `gradlew build` through Minecraft's `Done!` line. It covers the three big systems — dependency resolution, classloading, and bridge codegen — and shows what runs at build time vs. at runtime, with concrete bytecode examples for the bridge generator. It is intended for a maintainer or sophisticated mod author who has read the README. No prior knowledge of FML internals, classloader layers, or Sponge Mixin assumed; we explain those as we go.

For higher-level architectural decisions, see the ADRs (`docs/adr/`). For consumer-facing config, see `docs/bridges.md`.

## 1. The problem in one paragraph

Minecraft modding tightly couples every mod to a single shared classloader. JVM-language mods (Scala, Kotlin) need their stdlib on the classpath, but the stdlibs collide across mods (cats v2 vs v3, Kotlin 1.9 vs 2.0). mcdp gives each mod its own `URLClassLoader` so stdlib + library versions are fully isolated, and bridges the gaps where the platform expects shared identity (Mixin, MC types, NeoForge events).

## 2. Three big systems

```
┌──────────────────────────────────────────────────────────────────────┐
│ DEPENDENCY RESOLUTION                                                │
│   build-time: walk mcdepImplementation, SHA-256 each jar,            │
│               HEAD-probe Maven repos, write META-INF/                │
│               mcdepprovider.toml                                      │
│   runtime:    ManifestConsumer reads TOML, downloads on cache miss,  │
│               SHA-verifies, returns local Path                       │
└──────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│ CLASSLOADING                                                         │
│   per mod:  ModClassLoader (mod jar + bridge codegen output)         │
│             └─ parent: LibraryClassLoader (Scala/Kotlin/cats/...)    │
│                  └─ parent: provider's classloader                   │
│   shared:   PLATFORM_PREFIXES + sharedPackages → parent-first        │
│             everything else → child-first                            │
└──────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│ BRIDGE CODEGEN                                                       │
│   build-time: scan @Mixin/@EventBusSubscriber/... classes, rewrite   │
│               cross-classloader call sites to dispatch through       │
│               bridge interfaces, emit interface (shared pkg) +       │
│               impl (sibling _impl pkg) + manifest                    │
│   runtime:    AUTO_BRIDGE_REGISTRY populated from manifest;          │
│               first GETSTATIC LOGIC_X triggers <clinit> →            │
│               resolveAutoBridgeImpl → Class.forName(impl, modLoader) │
│               → CHECKCAST → PUTSTATIC. Every later call goes through │
│               the cached impl.                                        │
└──────────────────────────────────────────────────────────────────────┘
```

## 3. What runs when

| Phase | Where | What happens |
|---|---|---|
| `gradlew build` — manifest task | Gradle JVM | Walk the `mcdepImplementation` configuration, subtract platform-provided artifacts (Loom modImplementation, MDG NeoForge stack), SHA-256 each jar, HEAD-probe configured Maven repos in order to find a canonical URL, emit `META-INF/mcdepprovider.toml`. |
| `gradlew build` — `:generateMcdpBridges` | Gradle JVM | ASM-walk every `.class` in `main.output.classesDirs` for class-level annotations matching `bridgedAnnotations`. For each match: scan bytecode → rewrite cross-classloader call sites → emit bridge interface + impl + manifest entries. Lambda sites (INVOKEDYNAMIC LambdaMetafactory) rewritten too. |
| `gradlew build` — `Jar` task | Gradle JVM | Layer rewritten classes + manifests onto the jar with `duplicatesStrategy=INCLUDE` so codegen output wins over the original compiled bytecode. |
| `gradlew prepareMcdpDevCache` | Gradle JVM | Hard-link Gradle-cached jars into `~/.cache/mcdepprovider/libs/<sha256>.jar` so dev runs never hit the network. |
| MC start — BootstrapLauncher | JVM startup | Loads modlauncher into the SERVICE module layer; Sponge Mixin into PLUGIN layer. mcdp not active yet. |
| MC start — language loader discovery | NeoForge `ServiceLoader<IModLanguageLoader>`; Fabric `PreLaunchEntrypoint` | NeoForge: `McdpLanguageLoader.<clinit>` installs a lazy populator into `McdpProvider`. Fabric: `McdpPreLaunch.onPreLaunch` eagerly walks every mcdp-tagged mod, builds per-mod `ModClassLoader`s, registers each. |
| MC start — `Bootstrap.bootStrap()` | Vanilla MC | Sponge Mixin transformers fire as MC classes load. The first GETSTATIC of any `LOGIC_*` field on a rewritten mixin triggers the mixin's `<clinit>`, which calls `McdpProvider.resolveAutoBridgeImpl(...)`. On NeoForge this fires *before* FML's `loadMod` phase; the lazy populator detects the empty registry on first miss, walks `LoadingModList`, and registers every mod up-front. |
| MC start — FML `loadMod` (NeoForge only) | FML | Per-mod entry. Idempotent — the populator already registered each mod via `ensureRegistered()`. |
| Runtime — every mixin call | Per-mod `ModClassLoader` | Merged-into-MC mixin code does `INVOKEINTERFACE LOGIC_X.method(...)`. JVM dispatches to the impl class on `ModClassLoader`. The impl's body INVOKE-STATICs the mod-private target; JVM resolves it via `ModClassLoader`'s parent chain (which has Scala/Kotlin via `LibraryClassLoader`). |

## 4. Dependency resolution deep-dive

### 4.1 The manifest

Each mcdp-tagged mod ships one file at `META-INF/mcdepprovider.toml`:

```toml
lang = "scala"
sharedPackages = ["com.example.api."]

[[library]]
coords = "org.scala-lang:scala3-library_3:3.3.4"
url = "https://repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.3.4/scala3-library_3-3.3.4.jar"
sha256 = "abc123..."

[[library]]
coords = "org.typelevel:cats-core_3:2.13.0"
url = "..."
sha256 = "..."
```

- **`lang`**: language tag (`java`, `scala`, `kotlin`). Picked up by `EntrypointAdapter` to build the `@Mod` instance correctly (ADR-0005).
- **`sharedPackages`**: package prefixes the per-mod `ModClassLoader` delegates parent-first instead of child-first. Bridge interface package is auto-added by the plugin; users add API namespaces they explicitly want shared.
- **`[[library]]`**: one per Maven coordinate in the transitive closure, with the canonical URL (HEAD-probed) and SHA-256 (computed locally at build time). ADR-0003 is the rationale for build-time resolution.

### 4.2 `mcdepImplementation` vs `implementation`

`mcdepImplementation` is opt-in (ADR-0014). Anything you put there goes into the manifest and gets served through the per-mod loader. Anything on `implementation` / `modImplementation` stays platform-provided (Loom or MDG handles it). The plugin's `mcdepPlatformView` resolves the union of `api`/`implementation`/`runtimeOnly`/`compileOnly` and subtracts platform-provided coordinates from the manifest set, so a transitive that's already on the platform classpath doesn't get downloaded twice.

### 4.3 Cache

```
Linux:    ~/.cache/mcdepprovider/libs/<sha256>.jar
macOS:    ~/Library/Caches/mcdepprovider/libs/<sha256>.jar
Windows:  %LOCALAPPDATA%\mcdepprovider\libs\<sha256>.jar
override: $MCDEPPROVIDER_CACHE
```

Keyed by SHA-256, so identical jars from any version of any mod coalesce on disk. `prepareMcdpDevCache` hard-links Gradle-cached jars in so dev runs never hit the network — the provider's runtime download path executes against the prepared cache.

### 4.4 Runtime download

`ManifestConsumer.resolve(library)`:

1. Compute cache path = `<libsDir>/<sha256>.jar`.
2. If exists, return it.
3. Else: HTTP GET the URL into a temp file, compute SHA-256 of the download, compare to `library.sha256()`. Mismatch → throw `IOException` with the coords. Match → atomic-rename into the cache.
4. Return the path.

The path becomes one URL on the per-mod `LibraryClassLoader`'s URL list.

### 4.5 SHA-keyed coalescing (ADR-0006)

`LoaderCoordinator` keys its library-loader cache by the *sorted concatenation of every library's SHA-256*. Two mods declaring the same library set → same key → one shared `LibraryClassLoader` instance. `cats-core_3-2.13.0` loaded once across N mods that pin that version. Two mods with different versions → different keys → separate loaders, full isolation per ADR-0001.

## 5. Classloading deep-dive

### 5.1 The chain

```
   FML's ModuleClassLoader (game-layer)
   │   defines: net.minecraft.*, net.neoforged.*, mod jars (auto-modules)
   │
   │ parent
   ▼
   provider classloader
   │   defines: de.lhns.mcdp.* (mcdp itself is just a NeoForge mod / Fabric mod)
   │
   ▲ ─── mod-side chain rooted here ───
   │
   ┌─ ModClassLoader (one per mod, child-first)
   │     URLs: mod jar(s) + bridge codegen output
   │     parent ▼
   ┌─ LibraryClassLoader (per mod, child-first; SHA-coalesced across mods)
   │     URLs: scala3-lib.jar + cats-core.jar + circe-parser.jar + ...
   │     parent ▼
   └─ provider classloader  (loops back to the top of the chain)
```

A `Class.forName("scala.collection.immutable.List", true, modClassLoader)`:

1. ModClassLoader checks its URLs (mod jar) — no.
2. Delegates to parent → LibraryClassLoader checks scala3-lib.jar — yes, defines it.
3. The `Class` is unique to this LibraryClassLoader instance. Another mod with a different scala3-lib SHA gets a different `Class` of the same FQN.

### 5.2 `PLATFORM_PREFIXES` (parent-first)

In `ModClassLoader.PLATFORM_PREFIXES`. Every entry in this list resolves parent-first so mods see the same `Class` as MC/NeoForge/Fabric/etc.:

| Prefix | Why |
|---|---|
| `java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`, `org.w3c.`, `org.xml.` | JDK platform classes — must be the system class. |
| `net.minecraft.`, `com.mojang.` | MC must be identical across the JVM. |
| `net.neoforged.`, `net.fabricmc.` | Loader types must be identical. |
| `cpw.mods.` | modlauncher / securejarhandler internals; we never shadow them. |
| `org.slf4j.`, `org.apache.logging.log4j.` | Logging — same instance is essential. |
| `de.lhns.mcdp.api.`, `de.lhns.mcdp.core.` | The mcdp API itself — every mod resolves to the same `McdpProvider`. |
| `org.spongepowered.asm.mixin.`, `org.spongepowered.asm.mixin.injection.` | Mixin runtime classes used by mod bytecode. |

### 5.3 `sharedPackages`

User-declared (in `mcdepprovider { sharedPackages.add(...) }`) plus auto-populated with the bridge package. Each entry uses prefix matching with a trailing dot, so `<bridgePackage>.` matches `<bridgePackage>.MyBridge` but **NOT** `<bridgePackage>_impl.MyBridgeImpl` — that distinction is load-bearing (next section).

### 5.4 Why bridge impls live in `<bridgePackage>_impl`

The bridge **interface** sits in the auto-shared `<bridgePackage>` and resolves parent-first to the same `Class` on every loader. Its body has no concrete logic; just method declarations.

The bridge **impl**'s body invokes mod-private/Scala targets directly. If the impl were in the same shared package, `ModClassLoader.loadClass` on the impl FQN would go parent-first to FML's loader, FML would find it on the mod jar's modulepath and define it, the impl's defining loader would be FML's, and the JVM-symbol-resolution rule says class names in its bytecode resolve via FML — which can't see Scala. CNF at first invocation.

So the impl lives in a *sibling* package: `<bridgePackage>_impl` (with underscore, not dot — the trailing-dot prefix in `sharedPackages` doesn't match it). `ModClassLoader.loadClass` for an impl FQN goes child-first, defines it locally, and the impl's defining loader is `ModClassLoader`. Symbols in its body resolve via `ModClassLoader`'s parent chain — which has Scala via `LibraryClassLoader`. Works.

This was the bug behind the fluidphysics `scala.math.Ordering` CNF; see ADR-0021 errata for the full story.

### 5.5 `expandDevRoots` — why dev needs help

In production, `IModFile.getFilePath()` returns a single jar that contains every class. In dev, MDG/Loom hands FML one path — typically `build/resources/main`, where META-INF lives — but the actual `.class` files are in sibling directories: `build/classes/java/main`, `build/classes/scala/main`, `build/classes/kotlin/main`, plus the codegen output `build/mcdp-bridges/classes`. Without all of those on `ModClassLoader`'s URL list, mod-private classes fall through to FML's parent loader and lose isolation.

`McdpLanguageLoader.expandDevRoots` walks up to the project's `build/` dir and adds every standard source-set output it finds. Production jars are unaffected (a jar has no sibling-dir siblings).

## 6. Bridge codegen — the hardest part

End-to-end on one example. The mod is a Scala mod with a Mixin into `Block` calling `MyMod.shouldCancel(state)`.

### 6.1 The original mixin source (what you write)

Plain Sponge-Common-style. No mcdp annotations.

```java
@Mixin(Block.class)
public abstract class BlockMixin {
    @Inject(at = @At("HEAD"), method = "isPossibleToRespawnInThis", cancellable = true)
    private void mymod$isPossibleToRespawnInThis(CallbackInfoReturnable<Boolean> cir) {
        if (MyMod.shouldCancel(this.toString())) cir.setReturnValue(false);
    }
}
```

```scala
object MyMod {
  def shouldCancel(s: String): Boolean = s.startsWith("minecraft:bedrock")
}
```

### 6.2 What javac emits (before rewrite)

The interesting bytes in the inject method:

```
ALOAD 0
INVOKEVIRTUAL java/lang/Object.toString()Ljava/lang/String;
INVOKESTATIC com/example/MyMod.shouldCancel(Ljava/lang/String;)Z
IFEQ L1
ALOAD 1
ICONST_0
INVOKESTATIC java/lang/Boolean.valueOf(Z)Ljava/lang/Boolean;
INVOKEVIRTUAL .../CallbackInfoReturnable.setReturnValue(Ljava/lang/Object;)V
L1: RETURN
```

The middle `INVOKESTATIC com/example/MyMod.shouldCancel` is the cross-classloader call site. After Sponge merges this method into MC's `Block`, that bytecode lives on MC's class. JVM resolves `com/example/MyMod` via MC's defining loader (FML), which can't see mod-private classes. Crash.

### 6.3 What the scanner sees

`AnnotationSeedScanner` walks compiled `.class` files and notices `@org.spongepowered.asm.mixin.Mixin` on `BlockMixin`. The class FQN goes into the seed set.

`BridgeMixinScanner.scan(BlockMixin.class.bytes)` walks every method's instructions. For each INVOKE/GET/PUT/LDC/INVOKEDYNAMIC opcode it asks `BridgePolicy.needsBridge(owner)`:

```
INVOKESTATIC com/example/MyMod.shouldCancel
  → needsBridge("com/example/MyMod")
    → not java./javax./..., not in sharedPackages, not bridgePackage
    → TRUE → record BridgeMember(STATIC_METHOD, "shouldCancel", "(Ljava/lang/String;)Z")
              keyed under target "com/example/MyMod"
```

Result: `MixinScanResult.REWRITABLE` with `targets = { "com/example/MyMod" -> [BridgeMember(...)] }`.

### 6.4 The bridge interface (`BridgeInterfaceEmitter` output)

Class file at `build/mcdp-bridges/classes/com/example/mod/mcdp_bridges/MyModBridge.class`:

```
public interface com.example.mod.mcdp_bridges.MyModBridge {
    boolean shouldCancel(String);    // STATIC_METHOD: same descriptor as the original
}
```

ASM bytecode shape:
```
class com/example/mod/mcdp_bridges/MyModBridge
  ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT
  super: java/lang/Object
  methods:
    ACC_PUBLIC | ACC_ABSTRACT  shouldCancel(Ljava/lang/String;)Z
```

This package is in `sharedPackages` → loaded parent-first → same `Class` on ModClassLoader and FML's loader.

### 6.5 The bridge impl (`BridgeImplEmitter` output)

Class file at `build/mcdp-bridges/classes/com/example/mod/mcdp_bridges_impl/MyModBridgeImpl.class`:

```
public final class MyModBridgeImpl implements MyModBridge {
    public MyModBridgeImpl() { super(); }
    public boolean shouldCancel(String s) { return MyMod.shouldCancel(s); }
}
```

The forwarder body:
```
ACC_PUBLIC  shouldCancel(Ljava/lang/String;)Z
  ALOAD 1                                               // load s (slot 0 is 'this')
  INVOKESTATIC com/example/MyMod.shouldCancel(Ljava/lang/String;)Z
  IRETURN
```

This package is **NOT** in `sharedPackages` (note the underscore: `<bridgePackage>_impl`, not `<bridgePackage>.impl`). `ModClassLoader.loadClass` for this FQN goes child-first; ModClassLoader defines the impl. `com/example/MyMod` and (transitively) Scala stdlib resolve via the impl's defining loader (ModClassLoader) → finds them via the LibraryClassLoader chain. ✓

### 6.6 The rewritten mixin (`MixinRewriter` output)

The original `BlockMixin.class` is replaced with a rewritten version. Three changes:

**(a) Synthetic field added** to hold the resolved bridge:
```
ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC
LOGIC_MyMod : Lcom/example/mod/mcdp_bridges/MyModBridge;
```

**(b) `<clinit>` injected (or prepended)** to populate the field on first reference:
```
LDC "com.example.mixin.BlockMixin"
LDC "LOGIC_MyMod"
INVOKESTATIC de/lhns/mcdp/api/McdpProvider.resolveAutoBridgeImpl
              (Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;
CHECKCAST com/example/mod/mcdp_bridges/MyModBridge
PUTSTATIC LOGIC_MyMod : Lcom/example/mod/mcdp_bridges/MyModBridge;
RETURN
```

**(c) Method body rewritten** — the call site `INVOKESTATIC MyMod.shouldCancel` becomes a stack-juggle + INVOKEINTERFACE through the LOGIC field:
```
ALOAD 0
INVOKEVIRTUAL java/lang/Object.toString()Ljava/lang/String;       // result on stack
ASTORE 1                                                           // spill arg to local 1
GETSTATIC LOGIC_MyMod : Lcom/example/mod/mcdp_bridges/MyModBridge;
ALOAD 1                                                            // reload arg
INVOKEINTERFACE MyModBridge.shouldCancel(Ljava/lang/String;)Z
IFEQ L1
...
```

Why spill+reload: pushing args is interleaved with pushing the LOGIC reference, and ASM's stack-rearrange operations get awkward fast. Spilling into a fresh local is simpler.

### 6.7 The TOML manifest (build-time output)

`build/mcdp-bridges/resources/META-INF/mcdp-bridges.toml`:

```toml
[[bridge]]
mixin = "com.example.mixin.BlockMixin"
field = "LOGIC_MyMod"
interface = "com.example.mod.mcdp_bridges.MyModBridge"
impl = "com.example.mod.mcdp_bridges_impl.MyModBridgeImpl"
```

The `mixin` key name is historical — for `@EventBusSubscriber`-tagged classes it holds the subscriber FQN. The runtime can't and doesn't need to distinguish.

### 6.8 Runtime: how it actually executes

Sequence on NeoForge from Sponge merge through Scala resolution:

1. **Sponge transforms `Block`** during early class-loading. The mixin's body is merged into `Block`'s bytecode. Sponge keeps a reference back to `BlockMixin` so `LOGIC_MyMod`'s GETSTATIC still resolves there.
2. **First reference to `Block`** (say, in `Bootstrap.bootStrap`'s call to `Blocks.<clinit>`) triggers MC class loading. The merged code references `BlockMixin.LOGIC_MyMod`.
3. **JVM runs `BlockMixin.<clinit>`** for the first time. Its first instruction is `LDC "com.example.mixin.BlockMixin"`.
4. **`McdpProvider.resolveAutoBridgeImpl("com.example.mixin.BlockMixin", "LOGIC_MyMod")`** runs. The `AUTO_BRIDGE_REGISTRY` is empty (FML's `loadMod` hasn't fired yet — `Bootstrap.bootStrap` runs before it).
5. **Lazy populator fires**: `McdpLanguageLoader`'s installed `Runnable` walks `LoadingModList`, runs `ensureRegistered(info)` for every mcdp-tagged mod. Each call reads the mod's `META-INF/mcdp-bridges.toml`, parses entries, and populates the registry.
6. **Lookup retries.** Finds the entry: `impl = "com.example.mod.mcdp_bridges_impl.MyModBridgeImpl"`, `modLoader = <the ModClassLoader for com.example>`.
7. **`Class.forName(implFqn, true, modLoader)`**. ModClassLoader.loadClass: not in `sharedPackages`, child-first → findClass → finds it in the codegen output dir (added to URL list by `expandDevRoots` in dev, included in the jar in prod). Defines it. The impl's defining loader is ModClassLoader.
8. **Instantiate** via no-arg ctor. Cache by key. Return as `Object`.
9. **`<clinit>` resumes**: CHECKCAST to `MyModBridge`. The interface is `Class`-identical on both loaders (sharedPackage parent-first), so the cast is verifier-clean. PUTSTATIC `LOGIC_MyMod`.
10. **Original call site executes**: `GETSTATIC LOGIC_MyMod` → `INVOKEINTERFACE MyModBridge.shouldCancel`. JVM dispatches to `MyModBridgeImpl.shouldCancel` (its actual class). The impl's body's `INVOKESTATIC com/example/MyMod.shouldCancel` resolves `MyMod` via its defining loader (ModClassLoader) → finds it on the mod jar's URL list → defines `MyMod`. `MyMod`'s body references `String.startsWith` → resolves via JDK class — already loaded everywhere. Returns. Done.

Every later call skips steps 3–9: the LOGIC field is already populated, so just GETSTATIC + INVOKEINTERFACE.

### 6.9 Lambda case

For `INVOKEDYNAMIC LambdaMetafactory` sites whose synthetic body touches mod-private code, the codegen emits a **per-site** bridge interface + impl pair, similar to the regular case.

**Before (a Scala-style closure inside a mixin method, capturing `int x`):**
```
ILOAD 1                                  // capture x
INVOKEDYNAMIC apply(I)Ljava/util/function/IntUnaryOperator;
  bsm: LambdaMetafactory.metafactory
  args: [(I)I, BlockMixin.lambda$body$0(I)I, (I)I]
```

**After (rewritten):**
```
ISTORE 2                                 // spill capture
GETSTATIC LAMBDA_BlockMixin_0 : LBlockMixin$Lambda0Bridge;
ILOAD 2                                  // reload capture
INVOKEINTERFACE BlockMixin$Lambda0Bridge.make(I)Ljava/util/function/IntUnaryOperator;
```

**The bridge impl's `make` body** (loaded by ModClassLoader, can resolve Scala):
```
ALOAD 0                                  // 'this' (impl instance)
ILOAD 1                                  // capture
INVOKEDYNAMIC apply(I)Ljava/util/function/IntUnaryOperator;
  bsm: LambdaMetafactory.metafactory
  args: [(I)I, BlockMixin$Lambda0BridgeImpl.lambda$body$0(I)I, (I)I]
ARETURN
```

The `lambda$body$0` synthetic from the original mixin is **embedded** verbatim on the impl class (same name, same descriptor). Since it's now defined by ModClassLoader, its body's mod-private/Scala references resolve. The original synthetic on the mixin class becomes dead code; Sponge tolerates it.

See ADR-0021 for the full design.

## 7. Annotation seed (ADR-0021)

The codegen seeds on classes whose class-level annotations match `bridges { bridgedAnnotations.set(listOf(...)) }`. Default list:

- `org.spongepowered.asm.mixin.Mixin`
- `net.neoforged.bus.api.EventBusSubscriber`

Both have `@Retention(RUNTIME)` so they survive into the class file and `AnnotationSeedScanner` finds them post-compile. `*.mixins.json` parsing was originally kept as belt-and-braces; it was removed in the cleanup pass since the annotation seed catches every standard Sponge config. Add custom annotation FQNs if your mod uses a registry-driving annotation FML loads via `Class.forName`:

```kotlin
mcdepprovider {
    bridges {
        bridgedAnnotations.set(listOf(
            "org.spongepowered.asm.mixin.Mixin",
            "net.neoforged.bus.api.EventBusSubscriber",
            "com.example.api.MyCustomRegistryAnnotation",
        ))
    }
}
```

## 8. FAQ

- **My mixin references a class header type (interface injection / mod-private super) — codegen errors.** Codegen never rewrites class headers (Sponge would copy the bad reference onto the target regardless). Add the package to `sharedPackages` so the type loads parent-first on both sides.
- **I want the hand-written `@McdpMixin` pattern instead.** `bridges { enabled.set(false) }` and use `McdpProvider.loadMixinImpl(MyBridge.class)` — see ADR-0008.
- **Why does the bridge interface only see Java-stdlib types?** It's in a shared package, FML defines it, and FML can't see Scala. So a bridge method that takes `scala.Function1` would fail at link time on the FML side. Convert to Java-callable signatures (e.g., `java.util.function.Function`) or pass values through `Object`.
- **Two mods, same library, same SHA — one classloader or two?** One. SHA-keyed coalescing (ADR-0006).
- **Two mods, same coordinates, different SHA?** Two — full version isolation (ADR-0001).
- **Where's the impl class actually defined at runtime?** ModClassLoader, because it lives in the sibling `<bridgePackage>_impl` package which is NOT in `sharedPackages`. This is what makes Scala references inside the impl resolve correctly. See §5.4.
- **What if my codegen-generated bridge package collides with my mod code?** Override the default: `bridges { bridgePackage.set("...") }`.

## 9. Pointers

- **ADRs**: `docs/adr/` — every architectural decision with the alternatives that were considered and rejected.
- **Pitfalls**: `docs/pitfalls.md` — chronological list of failure modes seen during development, with root causes and fixes.
- **Consumer guide**: `docs/bridges.md` — concise reference for mod authors (how to configure, what's auto, what to do when codegen reports an error).
- **Migration**: `docs/migrating-to-bridges-rename.md` — for anyone upgrading from the pre-rename DSL.

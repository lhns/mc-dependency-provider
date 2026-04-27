# MC Dependency Provider (`mcdp`)

A JVM-language mod provider for **Fabric** and **NeoForge** (Minecraft 1.21.1+, Java 21). Lets mods declare Maven dependencies and have them downloaded + loaded per-mod at runtime, instead of bundling or shadowing them into the mod jar.

First-class support for **Java, Scala, Kotlin** — one provider, one pipeline, pluggable entry points.

**Status:** pre-v0.1. Unit-tested core, TestKit-verified Gradle plugin, green composite build on a realistic Scala 3 + cats + circe dep graph. In-game server/client smokes next (milestone M4).

## Why

Two problems, one solution:

1. **JPMS rejects Scala-ecosystem jars.** `cats-kernel` ships packages named `byte`, `char`, `int`, etc. NeoForge's module layer pipeline calls `ModuleDescriptor.Builder.packages(...)` and blows up on those names. Existing Scala loaders work around this with a forked cats — which breaks compatibility with anything compiled against vanilla cats (circe, fs2, ...).
2. **Cross-mod version conflicts.** Two mods with different `cats-core` versions can't coexist on a shared classpath.

This provider loads each mod through its own `URLClassLoader`. Dep jars stay in the loader's **unnamed module** (so the JPMS keyword check never fires) and each mod sees its own copy of every dep (so version conflicts vanish). No bytecode rewriting, no library forks.

See [`docs/`](docs/) for the full architecture and the [ADRs](docs/adr/) for the decisions behind it.

## Quick start — mod author

Apply the Gradle plugin and declare deps like you would in any JVM project:

```kotlin
// build.gradle.kts
plugins {
    `java-library`
    scala
    id("de.lhns.mcdp") version "0.1.0"
}

dependencies {
    // Opt-in bucket: anything declared here (plus its transitive closure) is emitted
    // into META-INF/mcdepprovider.toml and served at runtime by mcdepprovider's
    // per-mod URLClassLoader. Platform deps stay on their normal configurations
    // (modImplementation on Loom, implementation under MDG) — they're not in the manifest.
    mcdepImplementation("org.scala-lang:scala3-library_3:3.5.2")
    mcdepImplementation("org.typelevel:cats-core_3:2.13.0")
    mcdepImplementation("io.circe:circe-parser_3:0.14.10")
}

mcdepprovider {
    lang.set("scala")                      // "java" | "scala" | "kotlin"
    sharedPackages.add("com.example.api")  // parent-first packages (Mixin bridge interfaces)
}
```

At build time, the plugin:

- Walks the resolved `mcdepImplementation` closure (transitive).
- Subtracts anything already provided by the platform (i.e. resolved through `runtimeClasspath` outside the mcdep bucket).
- Writes `META-INF/mcdepprovider.toml` into your mod jar with every remaining transitive dep's coords, URL, and SHA-256.
- Hard-links resolved jars into `~/.cache/mcdepprovider/libs/<sha>.jar` so dev-mode `runClient` / `runServer` hits the cache instead of re-downloading (ADR-0007).

At runtime, the Fabric / NeoForge adapter reads the manifest, downloads anything not yet cached, verifies SHAs, and builds a per-mod `URLClassLoader`. Your mod's entry point is instantiated through that loader.

### Mod entry point

Declared the normal platform way:

- **NeoForge** — annotate your entry class with `@Mod("modid")` exactly as on a vanilla NeoForge mod. The provider discovers it via `ModFileScanData`. Constructors accepting `IEventBus`, `Dist`, `ModContainer` (or any subset) are matched via FML's standard signature shapes. `@EventBusSubscriber`-annotated classes are scanned via NeoForge's own `AutomaticEventSubscriber.inject(...)` — no special-case code needed. The only mcdepprovider-specific line in `neoforge.mods.toml` is `modLoader = "mcdepprovider"`.
- **Fabric** — declare the entry on `fabric.mod.json` `entrypoints` with `"adapter": "mcdepprovider"` per entrypoint object. Otherwise vanilla: implement `ModInitializer` (or any other Fabric entrypoint interface), no-arg ctor.

Per-language details: **Scala** `object` resolves via `MODULE$`. **Kotlin** `object` resolves via `INSTANCE`. Class-form entries on either go through ctor dispatch.

## Mods with Mixins

Sponge Mixin is hosted by the game-layer classloader, but mod-private Scala/Kotlin classes live behind a per-mod `ModClassLoader` — so a mixin holding `import com.example.MyMod` throws `NoClassDefFoundError` at runtime. mcdp closes the gap automatically: the Gradle plugin scans your compiled mixin bytecode, emits a bridge interface plus a per-mod impl, rewrites the mixin's method bodies to dispatch through the bridge, and wires the impl in at mod load. You write a plain Sponge-Common-style mixin with direct calls to your mod code — no annotations, no manual `sharedPackages` entries for ordinary call sites. Codegen is on by default; there is nothing to add to your build.

For users who want explicit control there is an opt-out (`mixinBridges { enabled.set(false) }`) and a hand-written `@McdpMixin` pattern. See [`docs/mixin-bridge.md`](docs/mixin-bridge.md) and [ADR-0008](docs/adr/0008-mixin-via-bridge-pattern.md) for both paths and for the cases the codegen still defers to manual `sharedPackages` (interface injection, mod-private mixin superclasses, reflection on mod-private classes).

## Repository layout

```
deps-lib/            manifest schema, IO, HTTP/SHA consumer, Aether producer (build-time only)
core/                ModClassLoader, LoaderCoordinator, EntrypointAdapter + impls, Mixin bridge API
gradle-plugin/       manifest generation, dev-cache pre-warm, run-task classpath patch
fabric/              LanguageAdapter + PreLaunchEntrypoint
neoforge/            IModLanguageProvider
test-mods/           real-world test projects exercising the full stack via composite build
docs/                architecture + ADRs
.gitea/workflows/    CI (Gitea Actions, GitHub-compatible YAML)
```

## Building

```bash
./gradlew build                  # all subprojects, unit tests, TestKit
./gradlew :gradle-plugin:test    # TestKit integration
cd test-mods/scala-example && ../../gradlew generateMcdpManifest jar  # composite manifest smoke
```

Java 21 Temurin is assumed. The build works on Linux, macOS, and Windows (the library cache path adapts per OS).

## License

Apache License 2.0 — see [LICENSE](LICENSE).

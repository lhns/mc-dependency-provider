# mc-lib-provider

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
    id("io.github.mclibprovider") version "0.1.0"
}

dependencies {
    // Opt-in bucket: anything declared here (plus its transitive closure) is emitted
    // into META-INF/mclibprovider.toml and served at runtime by mc-lib-provider's
    // per-mod URLClassLoader. Platform deps stay on their normal configurations
    // (modImplementation on Loom, implementation under MDG) — they're not in the manifest.
    mcLibImplementation("org.scala-lang:scala3-library_3:3.5.2")
    mcLibImplementation("org.typelevel:cats-core_3:2.13.0")
    mcLibImplementation("io.circe:circe-parser_3:0.14.10")
}

mclibprovider {
    lang.set("scala")                      // "java" | "scala" | "kotlin"
    sharedPackages.add("com.example.api")  // parent-first packages (Mixin bridge interfaces)
}
```

At build time, the plugin:

- Walks the resolved `mcLibImplementation` closure (transitive).
- Subtracts anything already provided by the platform (i.e. resolved through `runtimeClasspath` outside the mclib bucket).
- Writes `META-INF/mclibprovider.toml` into your mod jar with every remaining transitive dep's coords, URL, and SHA-256.
- Hard-links resolved jars into `~/.cache/mc-lib-provider/libs/<sha>.jar` so dev-mode `runClient` / `runServer` hits the cache instead of re-downloading (ADR-0007).

At runtime, the Fabric / NeoForge adapter reads the manifest, downloads anything not yet cached, verifies SHAs, and builds a per-mod `URLClassLoader`. Your mod's entry point is instantiated through that loader.

### Mod entry point

- **Java** — a class with a public constructor (optionally taking platform objects like `IEventBus`, `ModContainer`).
- **Scala** — an `object` (its `MODULE$` is used) or a class with a constructor.
- **Kotlin** — an `object` (its `INSTANCE` is used) or a class with a constructor.

Declare it the normal platform way (Fabric `fabric.mod.json` `entrypoints`, NeoForge `neoforge.mods.toml`) — the provider dispatches on `lang` to pick the right adapter.

## Mixin on non-Java mods

Mixin's `TransformingClassLoader` is bypassed for mod classes loaded through a `ModClassLoader`, so Mixin doesn't directly transform Scala/Kotlin mod code. The workaround is a **bridge pattern**: put a thin Java `@Mixin` class in the mod that calls into a Scala/Kotlin implementation via `McLibProvider.loadMixinImpl(...)`. See [ADR-0008](docs/adr/0008-mixin-via-bridge-pattern.md) for the full shape.

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
cd test-mods/scala-example && ../../gradlew generateMcLibManifest jar  # composite manifest smoke
```

Java 21 Temurin is assumed. The build works on Linux, macOS, and Windows (the library cache path adapts per OS).

## License

Not chosen yet — will land before v0.1.

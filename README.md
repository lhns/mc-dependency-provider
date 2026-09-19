# MC Dependency Provider (`mcdp`)

A JVM-language mod provider for **Fabric** and **NeoForge** (Minecraft 1.21.1+, Java 21). Lets mods declare Maven dependencies and have them downloaded + loaded per-mod at runtime, instead of bundling or shadowing them into the mod jar.

First-class support for **Java, Scala, Kotlin** — one provider, one pipeline, pluggable entry points.

**Status:** v0.1.2 published to Maven Central (`de.lhns.mcdp:mcdp` runtime jar + `de.lhns.mcdp:gradle-plugin`); the next release publishes per-MC-band as `de.lhns.mcdp:mcdp-1.21` (and siblings `mcdp-1.20.6`, `mcdp-1.20`, `mcdp-1.18`, `mcdp-1.17`, `mcdp-26.1` as bands land). Fabric and NeoForge in-game smokes are green on 1.21.1; mixin-bridge codegen verified end-to-end against three real consumer mods plus the in-tree `mixin-example` test mod (Java + Scala + Kotlin handlers, including `@Inject(at=HEAD)` on a target class's `<clinit>`).

## Why

Two problems, one solution:

1. **JPMS rejects Scala-ecosystem jars.** `cats-kernel` ships packages named `byte`, `char`, `int`, etc. NeoForge's module layer pipeline calls `ModuleDescriptor.Builder.packages(...)` and blows up on those names. Existing Scala loaders work around this with a forked cats — which breaks compatibility with anything compiled against vanilla cats (circe, fs2, ...).
2. **Cross-mod version conflicts.** Two mods with different `cats-core` versions can't coexist on a shared classpath.

This provider loads each mod through its own `URLClassLoader`. Dep jars stay in the loader's **unnamed module** (so the JPMS keyword check never fires) and each mod sees its own copy of every dep (so version conflicts vanish). No bytecode rewriting, no library forks.

See [`docs/`](docs/) for the full architecture and the [ADRs](docs/adr/) for the decisions behind it.

### When to choose this loader

- **Stick with the built-in loader** (`javafml` on NeoForge, the default on Fabric) when your mod needs no external Maven deps, or they're small enough to JiJ / shade.
- **Use mcdp** when your mod needs external Maven libraries it would rather not bundle. That's the default situation for Scala and Kotlin mods (cats, circe, cats-effect, fs2, coroutines, ...) and often the right answer for Java mods pulling in large libraries such as Jackson, Protobuf, or Guava.

## Supported Minecraft versions

mcdp publishes per-Minecraft-band artifacts. Pick the band that matches your mod's target MC version:

| Artifact | MC versions | JDK | Loaders | Status |
|---|---|---|---|---|
| `de.lhns.mcdp:mcdp-1.17` | 1.17.x | 16 | Fabric + Forge | Scaffold (adapter pending) |
| `de.lhns.mcdp:mcdp-1.18` | 1.18.x | 17 | Fabric + Forge | Scaffold (adapter pending) |
| `de.lhns.mcdp:mcdp-1.20` | 1.20.1 | 17 | Fabric + Forge | Scaffold (adapter pending) |
| `de.lhns.mcdp:mcdp-1.20.6` | 1.20.6 | 21 | Fabric + NeoForge | Scaffold (adapter pending) |
| `de.lhns.mcdp:mcdp-1.21` | 1.21.1 | 21 | Fabric + NeoForge | **Shipped (v0.1.x as `mcdp:VERSION`; v0.2+ as `mcdp-1.21:VERSION`)** |
| `de.lhns.mcdp:mcdp-26.1` | 26.1.x | 21+ | Fabric + NeoForge | Scaffold (adapter pending) |

See [ADR-0023](docs/adr/0023-multi-mc-band-publication.md) for the band-selection rationale and what's deliberately out of scope (1.15.2, 1.16.x — Java 8 + Mixin 0.7 era).

## Quick start — mod author

Apply the Gradle plugin and declare deps like you would in any JVM project:

```kotlin
// build.gradle.kts
plugins {
    `java-library`
    scala
    id("de.lhns.mcdp") version "0.1.2"
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
    sharedPackages.add("com.example.api.") // parent-first packages (Mixin bridge interfaces)
}
```

(The trailing dot is load-bearing: entries are matched as raw name prefixes, so `"com.example.api"`
would also capture unrelated siblings like `com.example.apiInternal`. Always end an entry with `.`.)

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

## Mods with Mixins or annotation-driven side-loads

Sponge Mixin is hosted by the game-layer classloader, but mod-private Scala/Kotlin classes live behind a per-mod `ModClassLoader` — so a mixin holding `import com.example.MyMod` throws `NoClassDefFoundError` at runtime. The same problem hits any class FML side-loads from class-level annotations: NeoForge's `@EventBusSubscriber` registrar calls `Class.forName(fqn)` against FML's loader, freezing the subscriber's defining loader at FML and locking it out of Scala/Kotlin stdlib too. mcdp closes both gaps automatically: the Gradle plugin scans seeded classes (mixins from `*.mixins.json` plus any class with a configured class-level annotation — defaults cover `@Mixin` and `@EventBusSubscriber`), emits a bridge interface plus a per-mod impl, rewrites method bodies and `INVOKEDYNAMIC LambdaMetafactory` sites to dispatch through bridges, and wires the impls in at mod load. You write plain Sponge-Common-style mixins or NeoForge-style subscribers with direct calls to your mod code — no annotations, no manual `sharedPackages` entries for ordinary call sites. Codegen is on by default; there is nothing to add to your build.

For users who want explicit control there is an opt-out (`bridges { enabled.set(false) }`) and a hand-written `@McdpMixin` pattern. See [`docs/bridges.md`](docs/bridges.md), [ADR-0008](docs/adr/0008-mixin-via-bridge-pattern.md), [ADR-0018](docs/adr/0018-automatic-mixin-bridge-codegen.md), and [ADR-0021](docs/adr/0021-generalized-bridge-codegen.md) for the full story (including the cases the codegen still defers to manual `sharedPackages` — interface injection, mod-private mixin superclasses, reflection on mod-private class names).

## Repository layout

```
deps-lib/            manifest schema, IO, HTTP/SHA consumer, Aether producer (build-time only)
core/                ModClassLoader, LoaderCoordinator, EntrypointAdapter + impls, bridge API
gradle-plugin/       manifest generation, dev-cache pre-warm, bridge codegen, run-task classpath patch
fabric/              LanguageAdapter + PreLaunchEntrypoint          (1.21 band)
neoforge/            IModLanguageLoader                             (1.21 band)
multi/               band aggregator — bundles the band's fabric + neoforge/forge
                     shadowJars into one runtime jar. Publication is per-band
                     (ADR-0023), not a single unified artifact.
fabric-{1.17,1.18,1.20,1.20.6,26.1}/     sibling adapters per MC band
forge-{1.17,1.18,1.20}/                  Forge bands (<= 1.20.4; scaffolds)
neoforge-{1.20.6,26.1}/                  NeoForge bands (1.20.5+)
multi-{1.17,1.18,1.20,1.20.6,26.1}/      the matching band aggregators
                     Directory names carry the band; Gradle project names carry the
                     published artifactId, and the two differ for the aggregators:
                     multi/ → :mcdp-1.21, multi-1.20/ → :mcdp-1.20, and so on
                     (see the projectDir remappings in settings.gradle.kts). The
                     unsuffixed fabric/ and neoforge/ dirs are the 1.21 band and map
                     to :fabric-1.21 / :neoforge-1.21.
cli/                 mcdepprovider-prefetch — offline cache pre-population for modpack authors
test-mods/           real-world test projects exercising the full stack via composite build
                     test-mods/README.md tables all 16 and says which are in CI —
                     four are deliberately-excluded scaffolds (26.1 bands, Forge 1.17/1.18)
docs/                end-to-end "how it works" walkthrough + ADRs (decision history)
.github/workflows/   CI — canonical. Edit workflows here.
.gitea/workflows/    verbatim mirror for Gitea Actions; every file carries a
                     "MIRROR — do not edit here" header. publish.yml is deliberately
                     GitHub-only: Gitea Actions implements no `workflow_run` trigger
                     and the release gate shells out to the `gh` CLI, so its absence
                     from the mirror is intent, not drift.
```

For a deep walkthrough of the build → boot → runtime pipeline (with bridge bytecode examples), read [`docs/how-it-works.md`](docs/how-it-works.md).

## First-launch downloads

The first time a player launches a mod that uses `mcdp`, the adapter downloads any libraries not yet in `~/.cache/mcdepprovider/libs/` (or the OS-equivalent). With a large dep graph this can take a few seconds. Progress shows up in two places:

- **Server / log file** (always): `latest.log` carries `mcdp[<modId>]: resolving N libraries`, per-library completion lines with byte counts, and a closing `resolved in N ms`. Per-library start lines are at DEBUG (enable in `log4j2.xml` if you want them).
- **Client UI**:
  - **NeoForge** — short progress messages flicker on the FML loading screen (via `StartupNotificationManager`). The eager download path may run before the screen exists; in that case the adapter no-ops the UI side and the log channel still carries the same content.
  - **Fabric** — Fabric's `PreLaunchEntrypoint` runs *before* any in-game UI, so the splash never sees these events. The launcher window's stdout/stderr tail and `latest.log` are the feedback channels; the adapter prints one stderr banner at the start of the download phase to signal the wait.

Subsequent launches hit the cache and skip the network entirely.

### Restricting download sources (`MCDP_REPO_WHITELIST`)

Every download is SHA-256 verified, but the URL itself comes from the mod's manifest. For supply-chain hardening, set `MCDP_REPO_WHITELIST` to a comma-separated list of allowed URL prefixes:

```
MCDP_REPO_WHITELIST=https://repo1.maven.org/maven2/,https://maven.example.org/releases/
```

Any library whose URL does not start with one of the prefixes is rejected **before** the request is sent, so a tampered manifest can't reach an attacker-controlled repo even though the SHA check would have caught the bytes later. Unset or blank means no enforcement — the default, so existing installs keep working. Useful for modpack authors and locked-down servers.

## Known limitations

- **Mixins go through the bridge pattern.** A mixin class can't reference mod-private Scala/Kotlin types directly; calls are routed through a bridge interface living in a `sharedPackages` prefix. mcdp generates the bridges for you, but a few shapes still need manual `sharedPackages` entries — see [`docs/bridges.md`](docs/bridges.md).
- **First launch requires network.** Unless the cache is pre-populated with the [`mcdepprovider-prefetch`](cli/) CLI.
- **Stdlib duplication.** Mods pinning slightly different Scala/Kotlin stdlib versions each get their own copy; identical SHAs are coalesced onto one loader ([ADR-0006](docs/adr/0006-sha-keyed-classloader-coalescing.md)).
- **Cross-mod APIs must speak platform types.** Mod A's `List[String]` and Mod B's `List[String]` are different `Class` objects when each has its own stdlib. Use Java-native types or explicit serialization across mod boundaries.

## Building

```bash
./gradlew build                  # all subprojects, unit tests, TestKit
./gradlew :gradle-plugin:test    # TestKit integration
cd test-mods/scala-example && ../../gradlew generateMcdpManifest jar  # composite manifest smoke
```

Java 21 Temurin is assumed. The build works on Linux, macOS, and Windows (the library cache path adapts per OS).

## License

Apache License 2.0 — see [LICENSE](LICENSE).

# Changelog

All notable changes to mcdp (MC Dependency Provider) are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) (pre-1.0: minor versions may break).

## [0.2.0] - 2026-09-23

mcdp now supports Minecraft 1.17.1 through the 26.x line on Fabric, Forge and NeoForge. Each
MC version range gets its own artifact, called a "band".

### Breaking changes / Migration

- **The runtime artifact coordinate changed.** `de.lhns.mcdp:mcdp` is replaced by one artifact per
  MC band, `de.lhns.mcdp:mcdp-<band>` (#7, #32). The old `mcdp` artifact was the 1.21.1 build, so
  its direct replacement is `mcdp-1.21`. Pick the band for your MC version:

  | MC version        | Artifact                       | Java | Loaders           |
  |-------------------|--------------------------------|------|-------------------|
  | 1.17.1            | `de.lhns.mcdp:mcdp-1.17`       | 16   | Fabric, Forge     |
  | 1.18.2            | `de.lhns.mcdp:mcdp-1.18`       | 17   | Fabric, Forge     |
  | 1.19.2            | `de.lhns.mcdp:mcdp-1.19`       | 17   | Fabric, Forge     |
  | 1.20.1            | `de.lhns.mcdp:mcdp-1.20`       | 17   | Fabric, Forge     |
  | 1.20.6            | `de.lhns.mcdp:mcdp-1.20.6`     | 21   | Fabric, NeoForge  |
  | 1.21.1 only       | `de.lhns.mcdp:mcdp-1.21`       | 21   | Fabric, NeoForge  |
  | 1.21.10, 1.21.11  | `de.lhns.mcdp:mcdp-1.21.11`    | 21   | Fabric, NeoForge  |
  | 26.1, 26.2, 26.3  | `de.lhns.mcdp:mcdp-26`         | 21   | Fabric, NeoForge  |

  MC 1.21.2 to 1.21.9 have no band, because NeoForge's loader SPI changed repeatedly across that
  range (#32). 1.15.2 and 1.16.x are out of scope.
- **The Gradle plugin is still `id("de.lhns.mcdp")`.** Bump it to `0.2.0` as well. It now targets
  Java 17 rather than 21, so it also runs on Gradle 7.6 daemons, such as ForgeGradle 5 on 1.17 (#29).
- **On MC 26.x Fabric, depend on `mcdp-26` with plain `implementation`, not `modImplementation`.**
  The game ships deobfuscated, so Loom's identity mapping cannot remap mcdp's sources jar (#35).
- **Registering the same modId twice now throws `IllegalStateException`.** Before, the second
  registration silently built a second loader and left the first one stranded
  (`LoaderCoordinator.register`, `McdpProvider.registerMod`) (#47).
- **`sharedPackages` entries always match as whole packages.** Every entry is normalised to end in
  `.`, so `"com.example.api"` no longer also shares `com.example.apiInternal` (#24).
- **Mixin lambdas that capture `this` are rejected by the bridge codegen.** No correct rewrite
  exists for them, so the build warns (naming the mixin, method and synthetic method) and leaves
  that lambda unrewritten. See `docs/bridges.md` for how to restructure the code (#25, #28).
- **Rebuild your mods with plugin 0.2.0.** The generated bridge code changed shape. Bridge names now
  carry a hash of the target's fully qualified name (#25). Constructor bridges are now named `_new`
  and return the target type instead of `Object` (#41). No source changes are needed, because the
  plugin generates both sides into your jar.

### Added

- Per-band artifacts for eight MC bands (see the table above). Each band artifact bundles its
  Fabric adapter and its Forge or NeoForge adapter (#7, #17).
- **Forge support** for MC 1.17.1, 1.18.2, 1.19.2 and 1.20.1 (#7, #30, #31, #32). Set
  `modLoader = "mcdepprovider"` in `mods.toml` and annotate the entry class with `@Mod`.
  - Lifecycle events such as `FMLCommonSetupEvent` reach the mod's own event bus. The entry
    constructor can take `IEventBus`, `ModContainer` and `Dist` (#30, #34).
  - Mixin bridges (including the lazy bootstrap-time populator), cross-mod stdlib promotion and
    download progress logging all work on Forge (#30).
  - Mixins are mapped to their owning mod through the jar's `MixinConfigs` manifest attribute in
    production (#38).
  - Known gap: `@EventBusSubscriber` is not auto-registered on Forge. Register subscribers from
    your entry point.
- NeoForge 1.20.6 (FML 3.0.x) support (#7), plus a separate NeoForge port for the FML 10 SPI used
  by 1.21.10+ and 26.x (#32).
- MC 1.19 band, 1.21.10/1.21.11 band, and one band for the whole 26.x line (#32).
- Fabric `Class::member` entrypoints, with the same rules as fabric-loader's default adapter (#47):
  - `Class::FIELD` returns the value of a static field.
  - `Class::method` returns a proxy of the entrypoint interface.
  - `Class::<init>` returns a proxy that builds a new instance on each call.
  - Instance methods bind to a Scala/Kotlin `object`'s singleton.
- `McdpProvider.loaderForMixin(String mixinClassFqn)` returns the `ModClassLoader` that owns a
  mixin (#47).
- NeoForge progress messages now reach the early loading screen. See the matching Fixed entry
  (#44, #46).

### Fixed

- **NeoForge 1.20.6: `NoSuchMethodError: IModFile.getLoaders()`.** It crashed any mod with a
  bootstrap-time mixin and silently turned off stdlib promotion. The adapter now compiles against
  the SPI that 1.20.6 actually runs (#44).
- NeoForge loading-screen messages were never shown, because the adapter looked up
  `StartupNotificationManager` in the wrong package (#44, #46).
- On Forge and NeoForge, one broken mod manifest turned off stdlib promotion for every mod. Now
  only that mod is skipped, with a WARN naming it (#43, #44).
- NeoForge: a `LoadingModList` that was not built yet switched the lazy bridge populator off for
  good, so every later mixin failed with "no auto-bridge registered" (#44).
- Forge: jars containing several mods broke, because only the first `@Mod` got a language loader.
  Mods are now matched by modId (#43).
- Forge: every mod without mixins died in `loadMod` with `FileNotFoundException` (#34).
- Forge: lifecycle events were silently dropped for every mcdp mod (#30).
- Forge: generated bridge classes were missing from ForgeGradle dev runs
  (`ClassMetadataNotFoundException`) (#34).
- `META-INF/mcdp-bridges.toml` was packaged twice. ForgeGradle's reobf step rejected the jar (#34).
- The default bridge package was not a legal Java package for project names containing `.`, such as
  `example-1.20` (#34).
- An unknown `lang` value now fails with an error that names the mod, on Fabric, Forge and NeoForge
  (#42, #43, #44).
- Fabric: a malformed manifest now fails with an error that names the mod instead of a bare
  `ClassCastException`. A `devRoot` path this OS cannot parse is skipped with a WARN instead of
  crashing the boot (#42).
- Download sizes in the log are now formatted with `Locale.ROOT`. Before, a de_DE system logged
  `1,5 KB` (#42, #43, #44).
- `fabric.mod.json` shipped a literal `${version}` placeholder, so `depends: { mcdepprovider: ... }`
  could never be satisfied (#27).
- `validateSharedPackages` false positives:
  - array casts or `clone()` in shared classes (#28)
  - bridge-call descriptors in rewritten mixins (#40)
  - local-variable debug info (#41)
  - the plugin's own generated bridges (#26)
- `VerifyError: Bad type on operand stack` in mixins that store the result of a bridged constructor
  in a local (`T t = new T(); t.f();`) (#41).
- The lambda bridge path never worked. Four codegen defects are fixed, covering generic SAMs,
  method references, `altMetafactory`, and lambdas after a branch (#25).
- Two bridge targets with the same simple name (`a.Config` and `b.Config`) overwrote each other (#25).
- Re-running `generateMcdpBridges` without a recompile could silently drop every bridge (#26).
- Concurrency and robustness fixes:
  - A race in the lazy bridge populator caused spurious "no auto-bridge registered" errors (#24).
  - Concurrent registration of the same mod split `Class` identity (#37).
  - Concurrent library downloads into the cache failed with `AccessDeniedException` or
    `NoSuchFileException` (#37).
  - One bad mixin config aborted mixin-owner registration for all the configs after it (#37).
- Per-mod and library classloaders now load resources child-first, like classes. An isolated
  library's `reference.conf` or `META-INF/services` no longer resolves against the game (#24).
- The `MCDEPPROVIDER_CACHE` environment variable was ignored. The old `MC_LIB_PROVIDER_CACHE` still
  works as a fallback (#15).
- The dev-cache pre-warm now checks SHA-256 before trusting a local build, so a stale local jar is
  no longer used forever (#37).
- If a failure after registration was retried, the retry reported "already registered" and hid
  the real error. The retry now rethrows the original failure as the cause (#47).

### Internal

- Unit-test suites for every adapter on every band, tests that can actually fail (#36, #42–#45),
  and CI server cells for every band and loader plus nightly client cells (#29, #38, #46).
- Band build logic moved into `buildSrc` convention plugins, and the bridge codegen package was
  renamed to `de.lhns.mcdp.gradle.bridges` (#14–#19, #33, #39).

## [0.1.2] - 2026-05-03

### Fixed

- `@Inject(at = HEAD)` on a target class's `<clinit>`: bridges now self-initialise at every call site.
- The bridge codegen task now runs on every build, so incremental in-place rewrites can no longer leave
  stale bridges.

## [0.1.1] - 2026-05-02

### Added

- Progress reporting for first-launch dependency downloads.
- Tagged releases are published to Maven Central automatically.

## [0.1.0] - 2026-04-30

### Added

- First release, for MC 1.21.1 on Fabric and NeoForge, published as `de.lhns.mcdp:mcdp` plus the
  `de.lhns.mcdp` Gradle plugin.
- Each mod loads through its own classloader. Its Maven dependencies are declared with
  `mcdepImplementation`, recorded in `META-INF/mcdepprovider.toml`, then downloaded, checked
  against SHA-256 and cached at runtime.
- Entry points in Java, Scala and Kotlin.
- Automatic bridge codegen for Mixins and `@EventBusSubscriber` classes, so they can call
  mod-private code.
- Mods that pin the same stdlib (identical SHA) share one copy.

[0.2.0]: https://github.com/lhns/mc-dependency-provider/compare/v0.1.2...v0.2.0
[0.1.2]: https://github.com/lhns/mc-dependency-provider/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/lhns/mc-dependency-provider/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/lhns/mc-dependency-provider/releases/tag/v0.1.0

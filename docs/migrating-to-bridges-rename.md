# Migrating to the bridges rename

If your mod's `build.gradle(.kts)` configures `mixinBridges { ... }`, or if your code references the `mcdp_mixin_bridges` package, you need to update for mcdp's rename. This is a one-shot rename — there's no lasting compatibility concern, just a few find-and-replace passes.

## What changed and why

mcdp's bridge codegen used to be mixin-specific. ADR-0021 generalized it to seed on any class-level annotation (mixins, NeoForge `@EventBusSubscriber`, custom registry annotations), so the "mixin" prefix in user-facing names became misleading. The rename keeps everything consistent before v0.1.0 ships.

## Side-by-side

| Before | After |
|---|---|
| `mixinBridges { }` DSL block in `build.gradle(.kts)` | `bridges { }` |
| `bridgePackage.set("...mcdp_mixin_bridges")` if you overrode the default | `bridgePackage.set("...mcdp_bridges")` |
| `:generateMcdpMixinBridges` task | `:generateMcdpBridges` |
| `MixinBridgesExtension` (Java type, only visible if you reach into the extension reflectively) | `BridgeCodegenExtension` |
| Build dir `build/mcdp-mixin-bridges/` | `build/mcdp-bridges/` |
| Jar resource `META-INF/mcdp-mixin-bridges.toml` | `META-INF/mcdp-bridges.toml` |
| Generated bridge package `<group>.<mod>.mcdp_mixin_bridges` (default) | `<group>.<mod>.mcdp_bridges` |
| Generated bridge impl package `<group>.<mod>.mcdp_mixin_bridges_impl` (default) | `<group>.<mod>.mcdp_bridges_impl` |
| Auto-shared package: `<group>.<mod>.mcdp_mixin_bridges.` | `<group>.<mod>.mcdp_bridges.` |
| Doc filename `docs/mixin-bridge.md` | `docs/bridges.md` |

## Step-by-step

1. **Update `build.gradle(.kts)`.** Replace `mixinBridges { ... }` with `bridges { ... }`. If the block sets `bridgePackage` explicitly with a literal containing `mcdp_mixin_bridges`, swap the literal too.

   Before:
   ```kotlin
   mcdepprovider {
       mixinBridges {
           bridgePackage.set("com.example.mymod.mcdp_mixin_bridges")
       }
   }
   ```
   After:
   ```kotlin
   mcdepprovider {
       bridges {
           bridgePackage.set("com.example.mymod.mcdp_bridges")
       }
   }
   ```

2. **Clean build.** `./gradlew clean build`. The codegen output dir moved from `build/mcdp-mixin-bridges/` to `build/mcdp-bridges/`; without a clean, the old dir lingers but isn't picked up.

3. **(Rare) Hand-written code referencing the bridge package.** If a non-mixin source file imports a generated bridge interface — typically only the codegen references them, but if you have a hand-rolled call site through one — update the package literal from `<...>.mcdp_mixin_bridges` to `<...>.mcdp_bridges`. Find-and-replace works.

## What did NOT change

- The `mcdepImplementation` Gradle configuration name.
- `META-INF/mcdepprovider.toml` (the dependency manifest, distinct from the bridges manifest).
- `@McdpMixin` and `McdpProvider.loadMixinImpl(...)` (the hand-written-bridge ADR-0008 path).
- `mcdepprovider { sharedPackages.add(...) }` and `mcdepprovider { lang.set(...) }` DSL.
- Default `bridgedAnnotations` list (still `Mixin` + `EventBusSubscriber`).

## Troubleshooting

- *"Could not find method `mixinBridges()` for arguments [...]"* — your build still uses the old DSL name. Rename to `bridges`.
- *Class not found at runtime: `<group>.<mod>.mcdp_mixin_bridges.SomethingBridge`* — your jar was built with the old plugin and is being loaded by a newer mcdp. Rebuild with the new plugin (composite-build users: `./gradlew clean build`; users pinning the plugin via Maven: bump the plugin version).
- *Stale `mcdp-mixin-bridges.toml` in jar* — clean build. The file is generated at codegen time; an incremental build can leave the old name behind.

## Pointers

- Why the rename happened: ADR-0021 §"The `*.mixins.json` seed is dropped" plus the cleanup commit history.
- The bigger picture (build-time vs runtime, classloader chain, bridge bytecode shapes): `docs/how-it-works.md`.
- Consumer-facing reference for the post-rename DSL: `docs/bridges.md`.

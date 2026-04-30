# Pitfalls and gotchas

Each entry is a real failure mode seen during mcdepprovider development, the
symptom, the root cause, and the fix. Kept chronologically within each section
so newer entries appear last.

## Classloading

### Aggregate library loader was parent-first

**Symptom.** Two Fabric mods pinning different `cats-core` versions both
printed the same `System.identityHashCode(cats.Functor.class.getClassLoader())`
— the Knot classloader's id. Version isolation (the ADR-0001 contract) was
invisible in dev mode.

**Root cause.** `LoaderCoordinator` built a SHA-keyed aggregate
`URLClassLoader` for each mod's libraries. `URLClassLoader.loadClass` is
parent-first by default. When `ModClassLoader` (child-first) failed to find
`cats.Functor` in its own URLs and delegated to the libs loader, that loader
asked Knot *first*. In a Loom dev-mode run, the mod's `implementation(cats…)`
already sits on Knot's classpath, so Knot returned its own copy and the
library jar's bytes were never loaded through the per-mod URL list.

**Fix.** New `LibraryClassLoader` with the same "child-first except platform
prefixes" policy `ModClassLoader` uses. Library bytecode now resolves out of
the library URLs before any parent is consulted. See commit `ea0ce4f`.

### `ModContainer.getRootPaths()` returns only `build/resources/main` in dev

**Symptom.** Even after the library-loader fix above, v1's entry class
`com.example.ExampleMod` resolved through Knot, not the per-mod
`ModClassLoader`. Adapter diagnostic showed
`entryClassLoaderName=net.fabricmc.loader.impl.launch.knot.KnotClassLoader` /
`entryLoaderMatches=false`.

**Root cause.** Loom in dev-mode exposes the mod through FabricLoader as a
single root path: `build/resources/main`. The compiled classes live in
`build/classes/{java,scala,kotlin}/main`, which are on Knot's classpath but
not on the ModContainer. Our ModClassLoader had only the resources dir on
its URL list, so `findClass(com.example.ExampleMod)` failed and the loader
fell through to Knot, which had the class on its classpath.

**Fix.** `McdpPreLaunch.expandDevRoots` walks from each root's
`build/` parent to `classes/*/main` siblings and adds the ones that exist to
the ModClassLoader's URL list. `LoaderCoordinator.register` gained a
`List<Path> modPaths` overload.

### Off-by-one in the `build/classes/*/main` walk

**Symptom.** First implementation of `expandDevRoots` silently added nothing,
so v1's entry class still resolved through Knot.

**Root cause.** Given `<project>/build/resources/main`, the walk did
`parent.getParent().getParent()` (→ `<project>`) then
`<project>.resolve("classes")`, which doesn't exist. The correct level is
`<project>/build`, one step up, not two.

**Fix.** Use `grand.resolve("classes")` where `grand = parent.getParent()`.

### Mixin bridge fails with two mods; works with one

**Symptom.** Single-mod boot applies the `@McdpMixin` bridge fine. Drop a
second mod in and boot crashes with
`IllegalStateException: loadMixinImpl: no ModClassLoader registered for mixin
net.minecraft.server.MinecraftServer (modId='')`.

**Root cause.** `McdpProvider.loadMixinImpl` uses `StackWalker` to find the
calling class, which it expects to be the `@McdpMixin`-annotated mixin
class. Mixin typically merges the annotated method body into the target
class — so the runtime stack frame under the static-field initializer is
`MinecraftServer.<clinit>`, not the original mixin class. The single-mod
fallback `if (MOD_LOADERS_BY_ID.size() == 1) return the only one` was
masking the issue.

**Fix.** Shipped post-v0.1 (commit `f438ee4`): each mod's mixin configs are
parsed at PreLaunch and `registerMixinOwner` is called ahead of time. The
fallback "if there's only one registered mod, return it" was removed at the
same time so the failure mode is now immediate and unambiguous.

### `ClassNotFoundException: scala.math.Ordering` mid chunk-gen, no mod frame

**Symptom.** A NeoForge mod with a mixin containing a Scala-style closure
(e.g., `Comparator.comparingInt(o -> o.someScalaCalc())`) crashes during
chunk generation with `CNF: scala.math.Ordering`. The stack is pure MC code
(`PlacedFeature.placeWithContext` → stream → CNF), no mod frame visible.

**Root cause.** Scala-emitted closures inside a mixin compile to
`INVOKEDYNAMIC LambdaMetafactory` plus a synthetic `lambda$body$N` on the
mixin class. ADR-0018 v1 had no `INVOKEDYNAMIC` branch in the rewriter, so
the indy and synthetic both passed through unchanged. After Sponge merges
the mixin into the MC target, the synthetic body lives on MC's class. JVM
resolves the synthetic body's class names via MC's defining loader (FML's
`ModuleClassLoader`) — which can't see Scala. Crash.

**Fix.** Two-part. (1) ADR-0021 lambda-wrapper codegen handles the
`INVOKEDYNAMIC` case. (2) **More importantly for fluidphysics specifically**,
the same ADR's errata moves bridge impls into a sibling package
(`<bridgePackage>_impl`) outside `sharedPackages`. Without the impl-package
fix, even simple non-lambda bridge calls into Scala fail (the impl's body
references Scala types directly, and the impl was being defined by FML's
loader because parent-first delegation kicked in for the auto-shared bridge
package). The lambda case happens not to hit fluidphysics at all (the
scanner finds zero LambdaMetafactory sites in fluidphysics's mixins) — the
real CNF was always the impl-loader leak, surfaced by fluidphysics being
the first real-world Scala consumer.

### `@EventBusSubscriber` with Scala-touching static handlers

**Symptom.** A mod with a `@EventBusSubscriber`-annotated class whose static
handler calls Scala / Kotlin / mod-private code crashes at server start when
NeoForge registers the subscriber. NCDFE on the first Scala-stdlib reference
inside the handler.

**Root cause.** NeoForge's automatic-subscriber registrar reads the
annotation via ASM and calls `Class.forName(fqn)` against FML's loader. The
defining loader of the subscriber class is FML's, so any non-platform
reference inside its body resolves via FML — which can't see mcdp's per-mod
deps. Same JVM rule that hits the lambda case above.

**Fix.** ADR-0021 generalized seeding. The codegen now also seeds on
class-level annotations (default list includes `@EventBusSubscriber`); the
existing bridge plumbing rewrites the subscriber's body so cross-classloader
calls go through bridges resolved by `ModClassLoader`. No mod-author action
needed beyond applying the plugin.

## Caching

### Loom's `.gradle/loom-cache` holds the remapped adapter indefinitely

**Symptom.** `publishToMavenLocal` wrote a fresh `fabric-0.1.0-SNAPSHOT.jar`
but the running server kept loading the old bytecode. Adding diagnostic
`println`s to the adapter appeared to have no effect.

**Root cause.** Loom remaps `modImplementation(...)` artifacts into
`<test-mod>/.gradle/loom-cache/remapped_mods/...` the first time they are
seen. Subsequent resolves for the same coordinate reuse the cached remap
even when the source jar in `mavenLocal` is newer. (The `build/loom-cache`
directory is unrelated — that's just mixin mapping scratch space.)

**Fix.** Delete `test-mods/<mod>/.gradle/loom-cache` before re-testing.
Treat it the same as a Gradle dep-cache when iterating on the Fabric
adapter.

### `ListProperty.convention(...)` is discarded as soon as a user calls `.add()`

**Symptom.** The gradle-plugin defined a set of mandatory Maven group
exclusions via `convention(...)` (Minecraft, NeoForge, Fabric, Mojang,
mcdepprovider). If a mod author called `excludeGroup("org.ow2.asm")` in
their own buildscript, all the mandatory exclusions vanished and the
generated manifest self-referenced `de.lhns.mcdp:*`.

**Root cause.** Gradle's lazy `ListProperty.convention(...)` is the value
used *only when the property has no other source*. Calling `.add()` on it
replaces the convention entirely — the property becomes "one added value",
the convention is gone.

**Fix.** Merge mandatory exclusions at task-wire-up time via
`ext.getExclusions().map(userList -> { list with mandatory always first })`
so they're always in the final value regardless of user additions.

(Both the `excludeGroup` DSL and the merge logic were later retired entirely
in favor of an opt-in `mcdepImplementation` configuration — the mod author
declares which deps go in the manifest instead of declaring which to leave
out. The `ListProperty.convention` gotcha still applies wherever the pattern
shows up.)

### `prepareMcdpDevCache` wasn't a dependency of run tasks

**Symptom.** `runServer` on a fresh machine hit
`mcdepprovider: failed to resolve libraries for <mod>` on first boot
because the SLP cache was empty.

**Root cause.** The gradle-plugin registered `prepareMcdpDevCache` but
never wired it as a dependency of the actual run tasks.

**Fix.** In an `afterEvaluate` hook, iterate `ext.getPatchRunTasks()` and
add `prepareCache` as a dependency of each listed run task.

### `URLClassLoader.loadClass` caching ignores the override parent

**Symptom (near-miss).** While wiring StdlibPromotion, an earlier version
of `LoaderCoordinator.register` reused the SHA-keyed cache entry regardless
of the `parentLibLoader` override. Two mods with the same reduced-lib SHA
set but different promoted parents could have collided into one
`LibraryClassLoader` with the wrong parent.

**Fix.** Cache key now includes the override parent's identity hash:
`aggregateKey + "|" + System.identityHashCode(parentLibLoader)`.

## NeoForge / FML 4.0.x

### `neoforgespi:9.0.2` ships the old `IModLanguageLoader` shape

**Symptom.** Implementing `IModLanguageProvider.IModLanguageLoader` with
a generic `<T> T loadMod` compiled but crashed at runtime with
`AbstractMethodError` or was silently skipped.

**Root cause.** FML 4.0.x flattened the old nested
`IModLanguageProvider.IModLanguageLoader` into a top-level
`net.neoforged.neoforgespi.language.IModLanguageLoader` with a non-generic
`ModContainer loadMod(IModInfo, ModFileScanData, ModuleLayer)`. The
standalone `neoforgespi:9.0.2` jar on Maven still carries the old shape;
the current `fancymodloader:loader` jar carries the new one.

**Fix.** Drop the `neoforgespi:9.0.2` compileOnly dep, keep only
`fancymodloader:loader`, and implement the top-level interface with the
non-generic signature.

### Valid `FMLModType` values are `MOD | LIBRARY | GAMELIBRARY` (no `LANGPROVIDER`)

**Symptom.** `Unrecognized FML mod-type: LANGPROVIDER` at boot; jar ignored.

**Root cause.** FML 4.0.x tightened the accepted values. `LANGPROVIDER` is
gone.

**Fix.** Declare `FMLModType: LIBRARY` in the shadow jar's manifest. Jars
that want to be discovered on the PLUGIN layer (where `ServiceLoader` scans
for `IModLanguageLoader`) must be LIBRARY, and must **not** carry a
`neoforge.mods.toml` — otherwise FML routes them as MODs and skips the
plugin layer.

### `IModInfo#getModProperties` only returns `[modproperties.<modId>]`

**Symptom.** Adding `entrypoint = "..."` as a top-level key inside
`[[mods]]` silently lost the value; `mod.getModProperties()` returned an
empty map.

**Root cause.** FML only exposes the `[modproperties.<modId>]` block
through `IModInfo.getModProperties()`. Inline keys on `[[mods]]` are
discarded.

**Fix.** Put any mcdepprovider-specific keys under
`[modproperties.<modId>]`.

(The original use case for this — declaring the entry class via
`[modproperties.<modId>] entrypoint = "..."` — was retired in favor of the
vanilla `@Mod` annotation discovered via `ModFileScanData.getAnnotatedBy`.
The `[modproperties.<modId>]` advice still applies for any future
mcdepprovider-specific keys.)

### `java.nio.file.FileSystems.newFileSystem(dir)` throws on dev-mode dirs

**Symptom.** `ProviderNotFoundException: Provider not found` when the
adapter tried to open a zip filesystem over the mod file to read the
manifest.

**Root cause.** In dev mode, `IModFile.getFilePath()` is a directory
(source-set output root), not a jar. There's no zip provider for
directories.

**Fix.** Use `IModFile#findResource(relativePath)` — FML exposes a unified
view over the mod's combined classes+resources that works in both jar and
dir cases.

### `shadowJar` JPMS split-package on `org.checkerframework` and `org.antlr`

**Symptom.** NeoForge boot: `ResolutionException: Package
org.checkerframework in both module A and module B` or similar for
`org.antlr`.

**Root cause.** `tomlj` transitively depends on `antlr` and pulls in
`checker-qual` annotations. The shadow jar contains those packages; the
NeoForge game module layer also has copies on its classpath as named
modules. JPMS refuses the split.

**Fix.** In `shadowJar`: `exclude("org/checkerframework/**")` and relocate
`org.tomlj` → `de.lhns.mcdp.shaded.tomlj`,
`org.antlr` → `de.lhns.mcdp.shaded.antlr`.

(Both relocations were retired entirely once `tomlj` was replaced with the
in-tree `MiniToml` parser. The `Manifest` schema is closed and we own both
sides of read/write, so a ~100-LOC purpose-built parser handles every shape
the producer emits without antlr's runtime overhead. Net result: fabric jar
748K → 52K, neoforge 556K → 52K. The fix-pattern above is still the right
answer whenever you have to bundle a JPMS-noisy library into a shaded mod
jar — kept here for that reuse.)

### NeoForge per-mod `loadMod` doesn't see all mods

**Symptom.** Applying StdlibPromotion naively on NeoForge required the full
manifest union, but `IModLanguageLoader.loadMod` is called once per mod.

**Fix.** Lazy-init on the first `loadMod` call: scan
`LoadingModList.get().getMods()`, filter by
`info.getLoader().name() == LANGUAGE_ID`, call
`StdlibPromotion.selectPromotions`, build the shared loader once, cache
under a double-checked `volatile`. Subsequent `loadMod` calls reuse the
cache. Fall back to empty-selection if `LoadingModList` isn't accessible
yet so the code path degrades to pre-promotion behavior rather than
crashing.

## Fabric / Loom

### `mods { create { dependency(...) } }` DSL doesn't exist in ModDevGradle 2.0.78

**Symptom.** `Unresolved reference: dependency` in the test-mod's
`build.gradle.kts`.

**Fix.** Use `implementation("de.lhns.mcdp:neoforge:0.1.0-SNAPSHOT")`.
FML auto-discovers the adapter on the runtime classpath via its bundled
`neoforge.mods.toml` + `META-INF/services/` entry.

### `localRuntime` config doesn't use project repositories

**Symptom.** `Could not find de.lhns.mcdp:neoforge`.

**Fix.** Add project-level
`repositories { mavenLocal(); mavenCentral(); maven("https://maven.neoforged.net/releases") }`
so the `implementation` dep resolves.

### `fabric.mod.json` `version = "${version}"` isn't expanded without `processResources` wiring

**Symptom.** FabricLoader warns
`Mod X uses the version ${version} which isn't compatible with Loader's
extended semantic version format`.

**Fix.** In the test mod's build:

```kotlin
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
```

## Minecraft runtime

### Port 25565 stays bound after a crashed run

**Symptom.** `java.net.BindException: Address already in use: bind` on the
second runServer within a few minutes, even though the previous process
crashed and `mc_smoke.py` exited.

**Root cause.** Minecraft shutdown after a first-tick failure doesn't close
the listening socket cleanly on Windows, and `mc_smoke.py` terminates the
Gradle worker but doesn't chase the child `java.exe`.

**Fix.** Before retrying, kill whatever owns 25565:

```bash
powershell -NoProfile -Command 'Get-NetTCPConnection -LocalPort 25565 \
    -ErrorAction SilentlyContinue \
    | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }'
```

### `eula.txt` must exist in `run/` or the server exits before first tick

**Fix.** `echo 'eula=true' > test-mods/<mod>/run/eula.txt` once per project.

## Build plumbing

### `cd <dir> && <cmd>` then starting a background process inherits the wrong CWD

**Symptom.** Repeatedly: "python: can't open file '.../.gitea/scripts/mc_smoke.py'".

**Root cause.** Bash chains that start with `cd test-mods/<mod> &&` leave
the working directory there for the rest of the chain. Running a
repo-relative script afterwards fails.

**Fix.** Use absolute paths to the script, or `(cd <dir> && <cmd>)` with
parentheses, or split into two separate invocations.

### Javadoc `{@code build/classes/*/main}` closes the comment early

**Symptom.** Twenty compilation errors about "class, interface, enum or
record expected" after adding a documentation comment containing `*/`.

**Fix.** Either escape the slash as `{@code build/classes/<lang>/main}` /
write it prose-style, or use a plain `//` comment instead of Javadoc.

### Gradle warns `LF will be replaced by CRLF` on every commit

**Symptom.** `warning: in the working copy of <file>, LF will be replaced
by CRLF the next time Git touches it` on every `git add`.

**Root cause.** Windows default `core.autocrlf=true` with files authored
as LF. Harmless — Git normalizes to LF in the index.

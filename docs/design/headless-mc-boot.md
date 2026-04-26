# Headless in-game MC boot — design for tasks #20 / #21

Status: design, pre-execution. The last infrastructure piece before M5 verification
(#22 / #23 / #24) and M5-7 publish (#32) unlock. Existing scaffolding
(`mc_smoke.py`, `.gitea/workflows/mc-smoke.yml`, `test-mods/*-example`) was built
with this design in mind and needs no structural change to execute it.

## Goal

Prove mc-lib-provider boots Minecraft 1.21.1 to first tick on each platform
without a GUI, via the same `runServer` path that CI uses. "Green" = two
successful runs per platform:

1. Cold cache: wipe `~/.cache/mc-lib-provider/` and the platform's MC asset
   cache, boot to the first-tick log marker, shut down cleanly.
2. Warm cache: re-run with network disconnected (or firewall-blocked); identical
   result. This pins the ADR-0007 dev-mode parity claim — no hidden network
   fallback outside the SHA-verified cache path.

Headless is strictly a server-side boot: `runServer` never opens a window, never
needs an OpenGL context, never needs xvfb. `runClient` (Tier-3 nightly) is out of
scope for #20/#21; that lives in `mc-client-nightly.yml` and can stay there.

## What "first tick" means

A `MinecraftServer` on 1.21.1 logs this once the world has loaded and the tick
loop starts. The regex `mc_smoke.py` watches for is:

```
Done \([\d.]+s\)! For help, type "help"
```

Real log sample from a clean NeoForge 21.1.x server:

```
[12:34:56] [Server thread/INFO]: Starting Minecraft server version 1.21.1
[12:34:59] [Server thread/INFO]: Preparing level "world"
[12:35:03] [Server thread/INFO]: Done (3.712s)! For help, type "help"
```

Once matched, `mc_smoke.py` sends SIGTERM to the gradle process; the server
catches that via its shutdown hook and writes `logs/latest.log` cleanly. The
script exits 0 on clean shutdown, 1 on timeout, 2 if a fatal pattern fired first.

Fatal patterns (already wired):

- `Invalid package name` — JPMS keyword check fired; ADR-0002 is broken.
- `java.lang.module.ResolutionException` — a dep leaked into a named module layer.
- `java.lang.NoClassDefFoundError` — a lib classloader chain is wrong.
- `java.lang.ClassCastException` — shared-package identity is not preserved.
- `mc-lib-provider: .*failed to` — our own diagnostics.

## Per-platform mechanics

### Fabric (task #21)

`./gradlew :runServer` under Loom 1.9:

1. Loom configures a `FabricMinecraftMod` run in `run/`; generates
   `run/eula.txt`. `mc_smoke.py` pre-writes `eula=true` so the first attempt
   doesn't abort on the EULA prompt.
2. Loom's Knot classloader boots, loads `fabric-loader`, which scans
   `mods/` + the Gradle dev-mode mods (ours) for `fabric.mod.json`.
3. The `mclibprovider` `languageAdapters` entry picks up `McLibLanguageAdapter`;
   `McLibPreLaunch` runs `ManifestConsumer` across all mods before
   `adapter.create` fires for any mod.
4. `ExampleMod.onLoad()` prints `[mclib-smoke] …` — the marker our Tier-2
   assertions look for. Then the normal `MinecraftServer` boot continues to
   first tick.

**Known Loom gotchas:**

- Loom resolves mappings (`net.fabricmc:yarn:1.21.1+build.3:v2`) on first boot
  — ~30 MB. Sensitive to Maven repo outages; the run will fail hard in
  `Task :downloadMappings`. Mitigation: `dependencyResolutionManagement` in
  `settings.gradle.kts` already includes Fabric Maven + Maven Central, so both
  need to be reachable the first time.
- The `run/` directory is relative to the project root when `gradlew` is
  invoked there. `mc_smoke.py` already does `cd` via `--cwd`.
- Loom spins a Gradle daemon by default. We pass `--no-daemon` so ctrl-C /
  SIGTERM propagates cleanly to the MC server process.

### NeoForge (task #20)

`./gradlew :runServer` under ModDevGradle, NeoForge 21.1.x:

1. MDG assembles a `RunModel` with MC + NeoForge on the server classpath plus
   our mod jar. `RunTaskClasspathPatch` (ADR-0007) strips manifest-listed deps
   from that classpath so the `runServer` classpath matches production.
2. NeoForge's bootstrap finds `IModLanguageLoader` via `ServiceLoader`; our
   `McLibLanguageLoader` registers for `modLoader = "mclibprovider"`.
3. For each mod declaring that loader in `neoforge.mods.toml`, `loadMod`
   reads the mod's `mclibprovider.toml`, invokes `ManifestConsumer`, then
   `LoaderCoordinator.register`. Returns a `FMLModContainer`-equivalent.
4. NeoForge drives event-bus construction, registry binding, server tick loop.

**Known NeoForge gotchas:**

- NeoForge 21.1.x SPI is sensitive to the exact `ModContainer` subclass
  returned from `loadMod`. `#16` is the ADR on that — we use an inner class
  extending `FMLModContainer` via reflection because the SPI is not exposed
  as an API. If NeoForge changes the constructor between 21.1.x patch
  versions, this breaks.
- ModDevGradle's first `runServer` downloads the NeoForm MC toolchain into
  `~/.neoforge` (~500 MB). Cache it aggressively.
- NeoForge's server writes `crash-reports/crash-*.txt` on fatal errors. Upload
  those as CI artifacts; we already do.

## Prereqs (local run)

Minimum environment on a dev machine:

| Item | Requirement | Check |
|---|---|---|
| JDK | 21 (Temurin tested) | `java -version` prints `21.x.x` |
| Network | First boot only | HEAD https://repo1.maven.org/maven2/ + https://maven.fabricmc.net/ + https://maven.neoforged.net/releases/ |
| Free disk | ~2 GB | `df -h` on Linux / `Get-PSDrive` on Windows |
| Ports | None required | server binds to 25565 but only on the loopback of its own box; no remote client |
| Display | None | runServer does not open a window |

On Windows specifically:

- The Windows Defender Firewall may prompt on the first `java.exe` run to
  accept incoming connections. The server still runs if denied — we only
  need outbound for downloads, and the tick loop doesn't require inbound.
- Hard-link pre-warm (`PrepareMcLibDevCacheTask`) needs the target cache dir
  on the same volume as the Gradle cache. On a default setup both live under
  `%USERPROFILE%`, so they're usually on C:. If the Gradle cache is on a
  different drive (via `GRADLE_USER_HOME`), the plugin falls back to copying.

## Runbook (local execution)

For each platform, run through:

```bash
# 0. Build the provider artifacts into the composite cache.
./gradlew build --no-daemon

# 1. Clean state — force cold cache.
rm -rf ~/.cache/mc-lib-provider
rm -rf test-mods/fabric-example/run/world test-mods/neoforge-example/run/world

# 2. First boot (cold).
python3 .gitea/scripts/mc_smoke.py \
    --cwd test-mods/fabric-example \
    --task :runServer \
    --timeout 900 \
    --log-file boot1-fabric.log

# Expect: exit 0, "Done (…s)!" in log, [mclib-smoke] line from ExampleMod.

# 3. Verify the classpath parity check (ADR-0007).
#    If the Gradle task :runServerClasspath is present, it dumps to
#    build/runServer-classpath.txt; grep for cats-*.jar etc — should be absent.

# 4. Second boot (warm).
#    On Linux: sudo ip link set <iface> down (requires sudo).
#    On Windows: Disable-NetAdapter -Name Ethernet (PowerShell admin).
#    On dev: `MCLIB_PROVIDER_HTTP_TIMEOUT_MS=1000` makes the consumer fail
#    fast if it tries to download anything. Our runtime respects it.
python3 .gitea/scripts/mc_smoke.py \
    --cwd test-mods/fabric-example \
    --task :runServer \
    --timeout 600 \
    --log-file boot2-fabric.log
```

Repeat for `test-mods/neoforge-example`. On success, the two logs should:

- Contain `Done (…s)!` once each.
- Contain `[mclib-smoke] mod=fabric_example name=mc-lib-provider cats.Functor.class.id=… cats.loader.id=…` once in each.
- Contain no `Invalid package name`, no `ResolutionException`, no
  `NoClassDefFoundError`, no `ClassCastException`.
- The cold log additionally shows `ManifestConsumer` fetching from a remote
  URL; the warm log shows only cache-hit lines (TBD on exact log format from
  `ManifestConsumer` — it's quiet today, which is fine because the absence of
  download lines proves the cache path).

## Failure-mode taxonomy

When a run fails, the diagnosis is:

| Symptom | Likely cause | Where to look |
|---|---|---|
| `SHA-256 mismatch for <coord>` | Manifest SHA stale vs Maven jar — mod rebuilt without regenerating manifest | `mclibprovider.toml` under `build/mc-lib-provider/META-INF/` |
| `Invalid package name: 'byte'` | Dep jar ended up in a NeoForge module layer instead of our URLClassLoader | ADR-0001 / ADR-0002; check `RunTaskClasspathPatch` fired |
| `ClassCastException: cats.Foo … cannot be cast to cats.Foo` | Shared-package prefix mis-set; both the mod loader and MC loader created a copy | `McLibProviderExtension.sharedPackages` |
| `NoClassDefFoundError: <provider internal>` | `McLibLanguageLoader` / `McLibLanguageAdapter` not packaged into the provider's own mod jar | check `neoforge/build/libs/` or `fabric/build/libs/` contents |
| Silent hang, no markers | Gradle is resolving mappings; check for `Task :downloadMappings` or similar in stdout | network reachability to the mappings repo |
| `HTTP 403` on a cats/circe URL | First declared Maven repo doesn't host that artifact; manifest points to the wrong repo | ADR on multi-repo URL resolution (#25 / `05dbb71`) — already fixed |

## Exit criteria for #20 / #21

Both tasks close when:

- Cold-boot `runServer` → exit 0, first-tick marker seen, no fatal pattern.
- Warm-boot `runServer` (offline) → exit 0, same.
- CI Tier-2 job `runserver-smoke` with both boot1 + boot2 steps green on
  `ubuntu-latest` and `windows-latest` for at least one push to `main`.
- Server log uploaded as a CI artifact shows no unexpected warnings that
  suggest upcoming breakage (deprecation warnings from NeoForge internals
  are tolerable; `ClassNotFoundException` stacktraces even if non-fatal are
  not).

Once #20/#21 close, #22 verify (version-iso) and #23 verify (mixin) unlock
automatically because their CI jobs run in the same workflow.

## Why this is the right sequence

Doing this end-to-end locally rather than bouncing it off CI repeatedly is
cheaper: each CI run is ~30 min and consumes a shared runner, vs ~15 min
the first time locally and ~5 min subsequent. We only need one successful
cold run locally to prove the design; CI then hardens against regressions.

This design is the last remaining blocker for M4. When it executes green,
M5 in-game smokes (#22/#23/#24) follow without design work — they reuse the
same `mc_smoke.py` orchestration and the same log-assertion pattern, just
with mod-specific smoke markers ([mclib-smoke] lines) driving the
assertions.

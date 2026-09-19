# ADR-0025 — Run-task classpath stripping is opt-in, not the default

**Status:** Accepted. Supersedes [ADR-0007](0007-dev-mode-parity.md)'s "strip the run task's classpath by default" decision; the rest of ADR-0007 (pre-warm the provider cache, leave `compileJava`/`test` alone) is unchanged.

## Context

ADR-0007 made dev runs exercise the production dep-loading path by stripping every manifest-listed jar off `runClient`/`runServer`/`runGameTestServer`/`runData` (`RunTaskClasspathPatch`), leaving the provider's `ManifestConsumer` + `ModClassLoader` pipeline as the only route by which deps reach the mod. That rested on an assumption about dev mode: that the mod's own classes are loaded through mcdp's `ModClassLoader` in dev just as they are in production.

ModDevGradle 2.0.91+ does not honour that assumption. It routes mod classes onto FML's **GAME-layer transformer** regardless of what mcdp does, and those classes need their compile-time dependencies (Scala stdlib, Kotlin stdlib, …) visible on that classloader chain. Stripping the jars breaks any mod whose code links against them from a game-layer path — world generation and other early hooks being the common repro. The default was therefore breaking working mods to enforce a parity property the platform no longer allows us to enforce.

## Decision

`mcdepprovider.patchRunTasks` defaults to the **empty list**: no run task is patched unless the user names one.

```kotlin
mcdepprovider {
    patchRunTasks.set(listOf("runServer"))  // strict isolation test, not normal dev
}
```

The stripping machinery itself is untouched — same SHA-matched filter, same `doFirst` hook. Only the convention changed, from "all four run tasks" to "none". Prod-parity dev runs become a deliberate diagnostic ("does my mod really work through per-mod isolation?") rather than the shape of everyone's edit-run loop.

## Consequences

**Positive:**
- Dev runs work out of the box on ModDevGradle 2.0.91+ for Scala/Kotlin mods; no mysterious link failures during world gen.
- The parity check survives as an explicit opt-in, so the behaviour ADR-0007 wanted is still reachable for anyone who wants it.
- The default no longer depends on an MDG/Loom internal that the platform is free to change.

**Negative:**
- **Dev and prod diverge by default.** Classloader-delegation, SHA-verification and cache-management bugs no longer surface automatically in dev — the ADR-0007 risk returns for anyone who does not opt in. CI Tier-2 smoke runs and explicit `patchRunTasks` runs are the compensating controls.
- One more knob whose default is "off", which reads as surprising to anyone who read ADR-0007 first. Hence this ADR.

## Alternatives considered

- **Keep stripping on by default and document the breakage.** Rejected: the failure mode is a mid-game `NoClassDefFoundError` with no mcdp frame on the stack — expensive to attribute, and it hits mods that are otherwise correct.
- **Strip only jars FML's game layer demonstrably doesn't need.** Requires modelling MDG's classpath routing per version; exactly the coupling that broke here.
- **Pin/shim ModDevGradle back to pre-2.0.91 behaviour.** Not ours to pin — the consumer picks their MDG version, and Loom has its own routing besides.

# ADR-0002 — Avoid JPMS keyword-package validation via unnamed modules

**Status:** Accepted

## Context

`cats-kernel.jar` — a transitive dep of nearly every typelevel Scala library and effectively unavoidable for Scala ecosystem work — ships directories literally named `cats/kernel/instances/byte/`, `char/`, `short/`, `int/`, `long/`, `float/`, `double/`, `boolean/`. These are package components named with Java keywords.

When a jar is added to a JPMS `ModuleLayer` without a `module-info.class`, NeoForge's `cpw.mods.securejarhandler` builds an automatic-module `ModuleDescriptor` from the jar's directory listing. `ModuleDescriptor.Builder.packages(Set<String>)` internally calls `Checks.requirePackageName(String)` on each entry, which rejects Java keywords: `Invalid package name: 'byte' is not a Java identifier`. The entire module layer fails to resolve. This is the core failure mode the FluidPhysics cats-integration doc documents.

Kotori's ScalableCatsForce works around this by maintaining a **forked cats** (`2.13.0-kotori`) with source-level package renames. That fork is incompatible with anything compiled against vanilla cats from Maven Central — most notably circe, which forces downstream mods to shadowJar-relocate cats into a private namespace. A workable path but a poor ecosystem foundation.

## Decision

Dependency jars are loaded via per-mod `URLClassLoader`s (see ADR-0001), **not** added to any JPMS module layer. Classes defined by such a loader end up in the loader's **unnamed module**.

`ModuleDescriptor.Builder.packages()` is only invoked when building a named-module descriptor (including automatic modules built from non-module jars). Classes in a loader's unnamed module are registered via internal APIs — `Module.implAddPackage(String)` or equivalent — that **do not call the keyword check**. The JVM itself has no objection to keyword-named package components in binary class names (JVMS §4.2.1 permits any non-`[/<>;` UTF-8 string); the keyword restriction is a Java-source-language concept, not a JVM one.

Concretely: `Class.forName("cats.kernel.instances.byte.ByteOrder", true, urlClassLoader)` loads successfully. The bytecode links, and references to `cats.kernel.instances.byte.*` resolve normally through the same loader.

We ship vanilla cats (no fork) and expect it to work.

## Consequences

**Positive:**
- Vanilla cats, circe, and any other Scala ecosystem library with keyword-named packages load without modification. No fork, no shading, no bytecode rewriting.
- The provider has zero ASM dependency at runtime. No `JarRewriter`, no `RewriteCache`, no `KeywordRefTransformer`. Footprint stays under 300 KB.
- Mods compiled against vanilla cats from Maven Central Just Work.

**Negative:**
- The claim "unnamed modules skip the keyword check" is load-bearing. Verified empirically in `ModClassLoaderTest` (verification step #1 in the plan). If a future JVM version tightens this, we'd need to add bytecode rewriting back.
- If a mod bundles a library with keyword packages inside its own jar (e.g. shadow-jars cats at `com/example/mymod/shaded/cats/kernel/instances/byte/`), that mod jar — which *is* added to NeoForge's module layer — will fail descriptor construction. Such mods are malformed under our system; the provider rejects them with a clear error rather than attempting to repair them.

## Alternatives considered

- **Bytecode rewriting at build time** (shadow relocation) — status quo for most cats-using mods. Works but 10 MB per mod and every author rediscovers the relocation dance.
- **Bytecode rewriting at runtime** (pre-rewrite downloaded jars, cache, plus `ILaunchPluginService` for mod bytecode) — an earlier version of this design. Feasible but unnecessary once we realized per-mod URLClassLoaders sidestep JPMS entirely. Kept mentally in reserve if verification #1 ever fails.
- **Forked cats** — rejected. It's the pain point the whole project is trying to get away from.

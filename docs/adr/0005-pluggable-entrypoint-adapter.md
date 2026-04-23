# ADR-0005 — Pluggable `EntrypointAdapter` for Java, Scala, Kotlin

**Status:** Accepted

## Context

Each JVM language expresses "mod entry point" slightly differently:

- **Java**: a plain class with a constructor. NeoForge expects one of `(IEventBus, ModContainer, Dist)`, `(IEventBus, ModContainer)`, `(IEventBus)`, or no-args.
- **Scala 2/3**: an `object` singleton compiles to a final class with a `MODULE$` static field holding the instance. Scala class-based mods also exist and use the same constructor chain as Java.
- **Kotlin**: an `object` singleton compiles to a final class with an `INSTANCE` static field. Kotlin class-based mods use plain constructors.

Everything else about loading the mod — resolving its Maven deps via the manifest, downloading libs into the cache, building a per-mod `ModClassLoader`, wiring into NeoForge/Fabric lifecycle — is **identical across all three languages**. The difference is the 10-line snippet that resolves a `Class` object to an instance.

Not having this as a clean extension point would either force a single language per provider (what every existing loader does — `javafml`, `fabric-language-scala`, etc.) or force the core code to hard-code per-language branches.

## Decision

A single interface in `core/`:

```java
public interface EntrypointAdapter {
    Object construct(Class<?> entryClass, ModContainer container, IEventBus bus);
}
```

Three implementations, one per language:

- **`JavaEntrypointAdapter`** — ctor-arity dispatch only.
- **`ScalaEntrypointAdapter`** — tries `MODULE$` static field first (for `object` singletons); falls back to the ctor chain.
- **`KotlinEntrypointAdapter`** — tries `INSTANCE` static field first (for `object` singletons); falls back to the ctor chain.

The mod's manifest declares which to use via the `lang` field: `"java" | "scala" | "kotlin"`. The provider instantiates the appropriate adapter and delegates.

## Consequences

**Positive:**
- **One provider supports three languages** without branching in core code paths. Adding a new language is one new adapter implementation.
- **Mod authors choose per-mod.** A mixed-language modpack Just Works — each mod declares its own `lang`.
- **Java-first design discipline.** The core runs as pure Java. The adapter interface is the only surface where language specifics leak in, and each adapter is ~30 LOC.

**Negative:**
- **Ctor-arity dispatch has to be reimplemented three times.** Most of each adapter is the same ctor-chain logic; only the "try MODULE$" / "try INSTANCE" preambles differ. A shared helper mitigates this, but can't fully deduplicate since the preamble decides whether to skip ctor dispatch entirely.
- **New language support needs a release.** A Groovy or Clojure user would need us to publish a new version, not just declare `lang = "groovy"` in their manifest. Acceptable — language support is an infrequent extension.

## Alternatives considered

- **One language per provider** — rejected. We'd need three separate projects (mc-java-provider, mc-scala-provider, mc-kotlin-provider) with 95% overlap. Shared infrastructure couldn't be factored out without exactly the `EntrypointAdapter` abstraction.
- **Runtime-loaded adapter classes (`Class.forName("io.github.mclibprovider.LangAdapterFor" + langName)`)** — rejected. Too clever; reflection-based extension for three adapters is not worth the indirection.
- **Let mods provide their own adapter class in the manifest** — rejected for MVP. Overengineered; no concrete user need. Could be added later without breaking the core abstraction.

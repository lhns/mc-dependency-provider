# ADR-0015 — In-tree closed-schema parsers (`MiniJson`, `MiniToml`)

**Status:** Accepted — `MiniJson` shipped in `784b1e9`, `MiniToml` shipped in `cc6df1b`.

## Context

mc-lib-provider's runtime needs to read two text formats:

1. **TOML** — the manifest file `META-INF/mcdepprovider.toml` written by the Gradle plugin and read by the platform adapters. We control both sides; the schema is fixed: three top-level keys and one array-of-tables with three string fields.
2. **JSON** — `fabric.mod.json` (read for the per-mod mixin-config list, ADR-0008 path 2) and Mixin's own `*.mixins.json` configs (read for the class-FQN list). Both schemas are externally specified, but we read a small fixed subset: in `fabric.mod.json` the `mixins` array; in `*.mixins.json` the `package` string and the `mixins`/`client`/`server` string arrays.

Initial implementation used `org.tomlj:tomlj:1.1.1` for TOML (which transitively pulls `antlr4-runtime` plus `org.checkerframework` annotations, ~550 KB combined) and would have used `com.google.code.gson:gson` for JSON. Both choices had visible costs:

- **JPMS split-package on `org.checkerframework` and `org.antlr`** under NeoForge's module-layer pipeline (recorded in `docs/pitfalls.md`). Required `exclude` + `relocate` rules in both fabric and neoforge `shadowJar` configurations to keep working.
- **gson is asymmetric across our two platforms.** NeoForge ships gson transitively via FML's `JarJarSelector` (free); Fabric Loader bundles an internal copy at `net.fabricmc.loader.impl.lib.gson.*` that's not safe public API. To use gson on Fabric we'd have to either bundle a second copy (~250 KB, more shading rules) or reflect against the internal — both worse than alternatives.
- **Jar-size impact.** With shaded tomlj+antlr the fabric and neoforge platform adapters were 748 KB and 556 KB respectively. After dropping tomlj (and never adopting gson): both 52 KB. Roughly 90% of the mass was generic-purpose parsing machinery for fixed schemas we control.

The recurring observation: when the format is small, fixed, and we own both sides (or read a tiny known subset), a hand-rolled parser is cheaper to maintain than a third-party dep.

## Decision

For text formats we read on the runtime path, **prefer in-tree purpose-built parsers** under the following conditions:

1. The schema is **closed** in one of two senses:
   - We own both writer and reader (TOML manifest), or
   - We read a small fixed subset of an externally-specified format (JSON inside `fabric.mod.json` / `*.mixins.json`).
2. The needed surface is **small** — strings, arrays, objects/tables, basic escapes. No datetimes, no number formats with quirks, no spec-compliant comment handling.
3. The parser is **boundary-only** (input may be malformed; we throw a typed diagnostic; the caller decides whether to fail load).
4. The parser stays **package-private** to its consumer module (`MiniJson` is package-private under `de.lhns.mcdp.core`; `MiniToml` is package-private under `de.lhns.mcdp.deps`). No exported general-purpose API.

Each parser carries a brief comment naming its scope and *what it deliberately doesn't handle*, so the next maintainer doesn't extend it incrementally into a partial implementation of the full spec.

### Current scope

| Parser | Lines (approx) | Reads | Doesn't handle |
|---|---|---|---|
| `de.lhns.mcdp.core.MiniJson` | ~150 LOC + 7 tests | Strings, numbers, booleans, nulls, objects, arrays, standard escapes, `\uXXXX`. | No surrogate-pair handling, no comments, no scientific notation beyond what `Double.parseDouble` accepts. |
| `de.lhns.mcdp.deps.MiniToml` | ~150 LOC + 12 tests | Top-level `key = "string"` and `key = ["a", "b"]`, `[[name]]` array-of-tables with string-keyed string fields, `#` comments to EOL, escape set the writer emits. | No nested tables, no inline tables, no dotted keys, no literal strings, no multi-line strings, no numbers/booleans/dates, no mixed-type arrays. Throws `IllegalArgumentException` with `@line N` context on anything outside that grammar. |

## Consequences

**Positive:**

- **Zero third-party runtime deps for parsing.** Both platform adapters now ship `de/lhns/mcdp/{api,core,deps,fabric|neoforge}/*.class` and nothing else. No relocations, no exclusions, no JPMS workarounds.
- **Symmetric across Fabric and NeoForge.** Same parser path, same diagnostics, same test coverage on both sides.
- **Free-standing test surface.** `MiniJsonTest` and `MiniTomlTest` are pure-JVM unit tests that don't need the platform classpath to run. Faster CI, easier debugging.
- **Documents the negative space.** "Doesn't handle …" sections in the source make it obvious what to refuse if a future feature would push us beyond the closed-schema premise.

**Negative:**

- **More code in the repo.** ~300 LOC across two parsers and ~20 unit tests. Maintainable in absolute terms, but it is code we own.
- **No upstream bug fixes for the schemas we don't fully implement.** If TOML's spec gains a new escape we'd want, we have to add it ourselves. Acceptable because our format is stable and writer-controlled (TOML) or trivially scoped (JSON subset).
- **Two parsers to keep in sync stylistically.** Both are line-based, both throw `IllegalArgumentException` with positional context, both are package-private singletons. The pattern is recognizable but enforced by convention, not by a shared base class.

## When to break this rule

Add a third-party parser when **any** of the following becomes true:

- The format is genuinely open-ended and consumers may write valid input we don't anticipate (e.g. user-authored YAML config with comments, anchors, multi-doc).
- The needed surface grows beyond ~200 LOC of parser logic. At that point we're approaching general-purpose territory and a maintained library is cheaper.
- The format has security-sensitive corners (XML XXE, JSON billion-laughs, etc.) where mistakes have outsized blast radius. Use a hardened parser.

For our current schemas (closed manifest TOML, three known JSON subsets), none of those apply.

## Alternatives considered

- **Keep tomlj.** Status quo at the point of decision. Rejected: 90% jar-size penalty for parsing 3 keys plus an array-of-tables.
- **Use NeoForge-shipped Night Config asymmetrically.** Free on NeoForge (FML transitively pulls it); would still require bundling on Fabric. Rejected for asymmetry — two code paths, more shading on Fabric than we already have.
- **Use `toml4j`.** Single ~80 KB jar, no antlr. Rejected because the project is dormant since 2018; if a TOML edge case bit us, no upstream to fix it. We'd own the bug regardless of whether we own the parser.
- **Use `minimal-json` for JSON.** ~35 KB jar, maintained. Rejected: same closed-schema reasoning. We read 4 distinct fields total across two file types; 150 LOC of `MiniJson` is less code in the repo than the dep declaration + Maven coordinate forever-pinning would imply, and ships zero bytes vs 35 KB × 2 platforms.

## Affected files

| Path | Role |
|---|---|
| `core/src/main/java/de/lhns/mcdp/core/MiniJson.java` | JSON reader. |
| `core/src/main/java/de/lhns/mcdp/core/MixinConfigScanner.java` | Caller of `MiniJson`. Reads `fabric.mod.json` mixin lists + Mixin config FQNs. |
| `core/src/test/java/de/lhns/mcdp/core/MiniJsonTest.java` | Direct parser tests. |
| `deps-lib/src/main/java/de/lhns/mcdp/deps/MiniToml.java` | TOML reader. |
| `deps-lib/src/main/java/de/lhns/mcdp/deps/ManifestIo.java` | Caller of `MiniToml`. Reads `META-INF/mcdepprovider.toml`. |
| `deps-lib/src/test/java/de/lhns/mcdp/deps/MiniTomlTest.java` | Direct parser tests. |

## Relationship to other ADRs

- Operates within **ADR-0004** (shared `deps-lib`). The `Manifest` schema lives there; this ADR is about *how* we read it.
- Annotates the JPMS split-package entry in `docs/pitfalls.md` as moot (the bug class can't fire because we no longer bundle the libraries that triggered it).

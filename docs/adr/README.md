# Architecture Decision Records

> **Note on naming.** The project was originally called `mc-lib-provider` and was renamed to **MC Dependency Provider** (`mcdp`) shortly before v0.1.0. ADRs 0001–0013 retain their original wording — they're records of decisions made at the time, and editing them in-place would obscure the historical context. Where an ADR mentions `mc-lib-provider`, `mclibprovider`, or class names like `McLibProvider`, mentally substitute the current names: `mcdp` / `mcdepprovider` / `McdpProvider`. Code paths and identifier strings have been updated everywhere except inside these ADR narratives.

This directory records the significant architectural decisions made for the project. Each ADR uses the MADR short form:

- **Context** — the problem and the constraints at the time of the decision
- **Decision** — what was decided
- **Consequences** — what this implies, positive and negative
- **Alternatives** — options considered and rejected, with reasons

When a decision is revisited, a new ADR supersedes (not edits) the old one, with a note in both.

## Status values

- **Accepted** — the decision is in force
- **Proposed** — drafted but not yet agreed
- **Superseded by ADR-NNNN** — no longer in force; the replacement ADR contains the current decision
- **Deprecated** — no longer relevant (e.g. the component it applied to was removed)

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-per-mod-urlclassloaders.md) | Per-mod URLClassLoaders for dependency isolation | Accepted |
| [0002](0002-avoid-jpms-via-unnamed-modules.md) | Avoid JPMS keyword-package validation via unnamed modules | Accepted |
| [0003](0003-build-time-dep-resolution.md) | Build-time dependency resolution with SHA-pinned manifests | Accepted |
| [0004](0004-shared-deps-lib.md) | Shared `deps-lib` library, build-tool-agnostic | Accepted |
| [0005](0005-pluggable-entrypoint-adapter.md) | Pluggable `EntrypointAdapter` for Java, Scala, Kotlin | Accepted |
| [0006](0006-sha-keyed-classloader-coalescing.md) | SHA-keyed classloader coalescing as the only optimization | Accepted |
| [0007](0007-dev-mode-parity.md) | Dev-mode runs through the production dep-loading path | Accepted |
| [0008](0008-mixin-via-bridge-pattern.md) | Mixin supported via a bridge pattern | Accepted |
| [0009](0009-reject-alternatives.md) | Rejected alternatives record | Accepted |
| [0010](0010-stdlib-version-promotion.md) | Opt-in stdlib version promotion | Accepted |
| [0011](0011-defer-sbt-and-maven-plugins.md) | Defer sbt and Maven plugins to v0.2 | Accepted |
| [0012](0012-dev-mode-classpath-grouping.md) | Dev-mode classpath grouping for composite-built multi-project mods | Accepted |
| [0013](0013-neoforge-faithful-entry-init.md) | NeoForge-faithful entry init via `@Mod` + FML helpers | Accepted |
| [0014](0014-opt-in-mcdep-implementation.md) | Opt-in `mcdepImplementation` Gradle bucket replaces `excludeGroup` | Accepted |
| [0015](0015-in-tree-closed-schema-parsers.md) | In-tree closed-schema parsers (`MiniJson`, `MiniToml`) | Accepted |
| [0016](0016-unified-mcdp-jar.md) | Unified `mcdp` jar bundling Fabric and NeoForge adapters | Accepted |

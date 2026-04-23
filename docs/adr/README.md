# Architecture Decision Records

This directory records the significant architectural decisions made for mc-lib-provider. Each ADR uses the MADR short form:

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

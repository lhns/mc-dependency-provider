# ADR-0026: `automaticRelease = true` — a green CI gate replaces the Portal click

**Status:** Accepted; supersedes [ADR-0020](0020-maven-central-publishing.md)'s `automaticRelease = false` decision. Everything else in ADR-0020 (vanniktech, Central Portal, the secret namespace, single-classloader plugin loading) stands.

## Context

ADR-0020 chose `automaticRelease = false`: release bundles would land in Central Portal staging and a maintainer would click "publish". The stated reason was an audit checkpoint for pre-1.0.

Two things changed after that decision:

1. **The workflow grew a real gate.** `.github/workflows/publish.yml` now refuses to publish a `workflow_run` firing unless *both* "CI — Tier 1 (unit + TestKit + manifest)" and "CI — Tier 2 (MC runServer smoke)" concluded `success` for that exact commit, and it runs `./gradlew build` again before uploading. That is a stricter check than a human reading a file listing in the Portal UI.
2. **One release is now seven bundles.** ADR-0023 fans publication out to six band aggregators plus `:gradle-plugin`. A manual gate that has to be worked through seven times per release is a gate that gets clicked through, and a bundle left un-clicked looks published (the tag exists, the workflow is green) while consumers cannot resolve it.

The flag was flipped in the code during that fan-out without the ADR being updated, which left ADR-0020 documenting a human staging gate that does not exist — the failure mode this ADR exists to close.

## Decision

`automaticRelease = true`. Release uploads call the Portal's release-now API and land on Maven Central without a manual step.

Set in exactly two places:

- `buildSrc/src/main/kotlin/mcdp.band-aggregator.gradle.kts` — all six band artifacts.
- `gradle-plugin/build.gradle.kts` — `:gradle-plugin`, which is not a band and so does not use the convention plugin.

Snapshots ignore the flag entirely; they always go straight to the Portal's snapshots repository.

The trust boundary moves from "a maintainer looked at it" to "Tier 1 and Tier 2 were green at this commit, and a full `build` passed on the release runner".

## Consequences

**Positive**

- Cutting a GitHub release is genuinely the only action needed to ship all seven artifacts.
- No partially-released release: either the gate passes and everything publishes, or nothing does.
- The gate is machine-checked and identical every time.

**Negative**

- **A release is irreversible.** Maven Central coordinates are immutable, so a bad release is fixed by publishing a new version, not by withdrawing the old one. Previously a misfire could be caught in staging.
- The gate only covers what Tier 1 and Tier 2 test. A defect neither tier exercises now reaches consumers unattended. The answer is to widen CI, not to re-add a click.
- `workflow_dispatch` and `release: published` runs bypass the Tier gate by design (there is no `workflow_run` context to check). Manual dispatches are therefore the one path with no gate at all — treat them accordingly.

## Alternatives

- **Keep `automaticRelease = false`.** Rejected: seven manual clicks per release is a gate in name only, and a forgotten click is indistinguishable from a successful release from the outside.
- **`false` for bands, `true` for `:gradle-plugin`** (or vice versa). Rejected: a release where some artifacts are live and others sit in staging is the worst of both, and consumers pin band and plugin together.
- **Keep the click and make it the *only* gate** (drop the CI gating). Rejected: the human check is weaker than the automated one, not stronger.

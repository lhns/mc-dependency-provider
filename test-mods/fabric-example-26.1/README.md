# fabric-example-26.1

Fabric test mod for the **MC 26.1.x** band (Mojang's calendar-versioning line),
targeting `de.lhns.mcdp:mcdp-26.1`.

**Deliberately not in CI.** MC 26.1.x is scaffold-only — Mojang has not shipped
real artifacts, and Loom errors with `26.1.2 requires Java 25 but Gradle is using
21`. Kept as a complete scaffold so enabling the band is a matrix-row edit.

See [`../README.md`](../README.md) and ADR-0023; the exclusion is also recorded in
the "Excluded from coverage" comment in `.github/workflows/mc-smoke.yml`.

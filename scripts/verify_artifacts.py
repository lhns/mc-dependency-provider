#!/usr/bin/env python3
"""
verify_artifacts.py — open what `publishToMavenLocal` actually produced and assert the
release contract on it, per band.

Why this exists: `"version": "${version}"` once shipped to Maven Central. The build was
green the whole time, because nothing in CI had ever opened a published jar. Every
assertion below is an assertion about jar/POM *contents*, not about the build succeeding.

Run after `./gradlew publishToMavenLocal`:

    python3 scripts/verify_artifacts.py --version 0.1.0-SNAPSHOT

Exit 0 when every band passes, 1 otherwise. Every failure is reported before exiting, so
one run shows the whole picture rather than the first broken band.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

GROUP = "de.lhns.mcdp"

# The release contract, restated here on purpose.
#
# These values are a *second copy* of what buildSrc/mcdp.band-adapter + the per-band
# `mcdpBand { }` blocks produce. Deriving them from the build files instead would make the
# check tautological: an edit that breaks a band would move the expectation with it and the
# jar would still "match". Sources for each column:
#   fabric_loader / java_release — the band's own fabric-<band>/build.gradle.kts `mcdpBand`
#                                  block (fabric-1.21's lives in fabric/).
#   fml_mod_type                 — ADR-0023 §"FMLModType per-band: LANGPROVIDER vs LIBRARY",
#                                  which also matches multi-<band>/build.gradle.kts.
#
# The LANGPROVIDER/LIBRARY split is the deliberate per-band difference: pre-NeoForge Forge
# bands (<= 1.20.4) are LANGPROVIDER; NeoForge bands (1.20.6+) are LIBRARY, because FML
# routes LIBRARY jars into the PLUGIN module layer it service-scans and rejects
# LANGPROVIDER there. A band silently flipped to the other value publishes fine and is
# simply never loaded — exactly the class of bug this file exists to catch.
BANDS = {
    "1.17":    {"fml_mod_type": "LANGPROVIDER", "fabric_loader": "0.14", "java_release": 16},
    "1.18":    {"fml_mod_type": "LANGPROVIDER", "fabric_loader": "0.14", "java_release": 17},
    "1.19":    {"fml_mod_type": "LANGPROVIDER", "fabric_loader": "0.14", "java_release": 17},
    "1.20":    {"fml_mod_type": "LANGPROVIDER", "fabric_loader": "0.15", "java_release": 17},
    "1.20.6":  {"fml_mod_type": "LIBRARY",      "fabric_loader": "0.15", "java_release": 21},
    "1.21":    {"fml_mod_type": "LIBRARY",      "fabric_loader": "0.16.0", "java_release": 21},
    "1.21.11": {"fml_mod_type": "LIBRARY",      "fabric_loader": "0.19", "java_release": 21},
    "26":      {"fml_mod_type": "LIBRARY",      "fabric_loader": "0.19", "java_release": 21},
}

# Published alongside the bands; carries no Fabric/FML metadata, so it is only coordinate-
# and placeholder-checked. Value is the POM <name>, which here is not the artifactId
# (gradle-plugin/build.gradle.kts sets it explicitly).
EXTRA_ARTIFACTS = {"gradle-plugin": "mcdp Gradle plugin"}

MOD_ID = "mcdepprovider"

# Tight enough that a stray `$` or `{` in bytecode cannot trip it, loose enough to catch any
# Gradle/Groovy template variable name. Matched against raw bytes so binary entries need no
# decoding guess.
PLACEHOLDER = re.compile(rb"\$\{[A-Za-z_][A-Za-z0-9_.\-]*\}")


class Report:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def check(self, scope: str, ok: bool, detail: str) -> bool:
        print(f"  {'ok  ' if ok else 'FAIL'} {scope}: {detail}")
        if not ok:
            self.failures.append(f"{scope}: {detail}")
        return ok


def parse_manifest(raw: bytes) -> dict[str, str]:
    """MANIFEST.MF, including the 72-byte continuation-line wrapping the JDK applies."""
    text = raw.decode("utf-8", "replace").replace("\r\n", "\n").replace("\r", "\n")
    lines: list[str] = []
    for line in text.split("\n"):
        if line.startswith(" ") and lines:
            lines[-1] += line[1:]
        else:
            lines.append(line)
    attrs = {}
    for line in lines:
        if ": " in line:
            key, _, value = line.partition(": ")
            attrs.setdefault(key.strip(), value.strip())
    return attrs


def pom_text(path: Path, tag: str) -> str | None:
    """First top-level <tag> of a POM. Deliberately not an XML parse: the POMs here are
    vanniktech-generated and flat, and a regex keeps this script dependency-free."""
    m = re.search(rf"<{tag}>([^<]*)</{tag}>", path.read_text(encoding="utf-8"))
    return m.group(1).strip() if m else None


def scan_placeholders(jar: Path, rep: Report, scope: str) -> None:
    with zipfile.ZipFile(jar) as z:
        for entry in z.namelist():
            if entry.endswith("/"):
                continue
            hits = PLACEHOLDER.findall(z.read(entry))
            if hits:
                found = ", ".join(sorted({h.decode() for h in hits}))
                rep.check(scope, False, f"{jar.name}!{entry} has unexpanded {found}")


def verify_coordinates(base: Path, artifact: str, version: str, pom_name: str,
                       rep: Report) -> Path | None:
    scope = artifact
    pom = base / f"{artifact}-{version}.pom"
    jar = base / f"{artifact}-{version}.jar"
    if not rep.check(scope, jar.is_file(), f"jar present ({jar})"):
        return None
    if not rep.check(scope, pom.is_file(), f"pom present ({pom})"):
        return None

    for tag, want in (("groupId", GROUP), ("artifactId", artifact),
                      ("version", version), ("name", pom_name)):
        got = pom_text(pom, tag)
        rep.check(scope, got == want, f"pom <{tag}> = {got!r} (want {want!r})")

    # Sources/javadoc jars ship too; a placeholder in any of them is still a shipped bug.
    for sibling in sorted(base.glob(f"{artifact}-{version}*.jar")):
        scan_placeholders(sibling, rep, scope)
    return jar


def verify_band(base: Path, artifact: str, version: str, spec: dict, rep: Report) -> None:
    jar = verify_coordinates(base, artifact, version, artifact, rep)
    if jar is None:
        return
    scope = artifact
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())

        if rep.check(scope, "fabric.mod.json" in names, "fabric.mod.json present"):
            meta = json.loads(z.read("fabric.mod.json"))
            got = meta.get("version")
            # The original incident, restated as an assertion: a literal template here is
            # unparseable as semver, so fabric-loader degrades it to a StringVersion and no
            # consumer's `depends: { mcdepprovider: ">=x" }` can ever be satisfied.
            rep.check(scope, got == version, f"fabric.mod.json version = {got!r} (want {version!r})")
            rep.check(scope, meta.get("id") == MOD_ID, f"mod id = {meta.get('id')!r}")
            depends = meta.get("depends", {})
            for key, want in (("fabricloader", f">={spec['fabric_loader']}"),
                              ("java", f">={spec['java_release']}")):
                rep.check(scope, depends.get(key) == want,
                          f"depends.{key} = {depends.get(key)!r} (want {want!r})")

        attrs = parse_manifest(z.read("META-INF/MANIFEST.MF"))
        got = attrs.get("FMLModType")
        rep.check(scope, got == spec["fml_mod_type"],
                  f"FMLModType = {got!r} (want {spec['fml_mod_type']!r})")
        rep.check(scope, attrs.get("Automatic-Module-Name") == MOD_ID,
                  f"Automatic-Module-Name = {attrs.get('Automatic-Module-Name')!r}")


def verify_band_list(settings: Path, rep: Report) -> None:
    """A new band added to settings.gradle.kts but not to BANDS would otherwise ship
    entirely unchecked."""
    declared = set(re.findall(r'include\("mcdp-([^"]+)"\)', settings.read_text(encoding="utf-8")))
    known = set(BANDS)
    rep.check("bands", declared == known,
              f"settings.gradle.kts bands {sorted(declared)} vs verified {sorted(known)}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Assert the release contract on published artifacts.")
    ap.add_argument("--version", required=True, help="Published version, e.g. 0.1.0-SNAPSHOT")
    ap.add_argument("--m2", default=str(Path.home() / ".m2" / "repository"),
                    help="Local Maven repository root")
    ap.add_argument("--settings", default="settings.gradle.kts",
                    help="settings.gradle.kts, cross-checked against the band table")
    args = ap.parse_args()

    root = Path(args.m2) / Path(*GROUP.split("."))
    rep = Report()

    print(f"repo: {root}\nversion: {args.version}\n")
    verify_band_list(Path(args.settings), rep)

    for band, spec in BANDS.items():
        artifact = f"mcdp-{band}"
        print(f"\n{artifact}")
        verify_band(root / artifact / args.version, artifact, args.version, spec, rep)

    for artifact, pom_name in EXTRA_ARTIFACTS.items():
        print(f"\n{artifact}")
        verify_coordinates(root / artifact / args.version, artifact, args.version, pom_name, rep)

    print()
    if rep.failures:
        print(f"FAILED: {len(rep.failures)} assertion(s):")
        for f in rep.failures:
            print(f"  - {f}")
        return 1
    print(f"OK: {len(BANDS)} bands + {len(EXTRA_ARTIFACTS)} extra artifact(s) verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

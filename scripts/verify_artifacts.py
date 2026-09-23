#!/usr/bin/env python3
"""
verify_artifacts.py — open what `publishToMavenLocal` actually produced and assert the
release contract on it, per band.

Why this exists: `"version": "${version}"` once shipped to Maven Central. The build was
green the whole time, because nothing in CI had ever opened a published jar. Every
assertion below is an assertion about jar/POM *contents*, not about the build succeeding.

Run after `./gradlew publishToMavenLocal`:

    python3 scripts/verify_artifacts.py --version 0.2.1-SNAPSHOT

Or against any Maven repository layout — the release dry run publishes to a local file
repository so it can exercise the signed, non-SNAPSHOT path without uploading anything:

    python3 scripts/verify_artifacts.py --version 0.0.0-dry-run \
        --m2 build/dry-run-staging --signatures require

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

# The Gradle plugin marker. `java-gradle-plugin` publishes a second, POM-only publication
# whose groupId is the *plugin id* and whose artifactId is "<plugin id>.gradle.plugin"; that
# is the coordinate `plugins { id("de.lhns.mcdp") }` resolves, and its single dependency is
# what redirects to the real jar. Here the plugin id happens to equal GROUP, so the marker
# lands in the same directory as the bands — that is a coincidence, not a rule, which is why
# the group is spelled out below rather than reusing GROUP.
PLUGIN_ID = "de.lhns.mcdp"
PLUGIN_MARKER_GROUP = PLUGIN_ID
PLUGIN_MARKER_ARTIFACT = f"{PLUGIN_ID}.gradle.plugin"
# The marker POM's <name>. vanniktech's `pom { name }` reaches the marker publication too, so
# this is gradle-plugin/build.gradle.kts's name, not the gradlePlugin{} displayName.
PLUGIN_MARKER_POM_NAME = "mcdp Gradle plugin"
# What the marker must point at: the artifact that actually carries the plugin classes.
PLUGIN_IMPL_ARTIFACT = "gradle-plugin"

MOD_ID = "mcdepprovider"

# Extensions a repository publish writes that are not themselves published content. A
# file-repository publish emits checksums; `publishToMavenLocal` does not. Both are excluded
# from the "every published file needs a signature" rule, and so is maven-metadata*.xml.
CHECKSUM_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")

SIGNATURE_HEADER = b"-----BEGIN PGP SIGNATURE-----"

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


def pom_dependencies(path: Path) -> list[tuple[str, str, str]]:
    """Every <dependency> of a POM as (groupId, artifactId, version). Same deliberate
    non-XML-parse as pom_text: these POMs are generated and flat."""
    deps: list[tuple[str, str, str]] = []
    text = path.read_text(encoding="utf-8")
    for block in re.findall(r"<dependency>(.*?)</dependency>", text, re.S):
        def field(tag: str) -> str:
            m = re.search(rf"<{tag}>([^<]*)</{tag}>", block)
            return m.group(1).strip() if m else ""
        deps.append((field("groupId"), field("artifactId"), field("version")))
    return deps


def signable_files(base: Path) -> list[Path]:
    """Everything in a version directory that the Central Portal treats as a published file:
    the jars, the POM and the Gradle module metadata — not signatures, not checksums, not
    repository metadata."""
    if not base.is_dir():
        return []
    return sorted(
        p for p in base.iterdir()
        if p.is_file()
        and not p.name.startswith("maven-metadata")
        and not p.name.endswith(".asc")
        and not p.name.endswith(CHECKSUM_SUFFIXES)
    )


def verify_signatures(base: Path, scope: str, rep: Report) -> None:
    """One armored `.asc` per published file.

    Central rejects a release bundle whose POM or jars are unsigned, and until this existed
    nothing checked that `signAllPublications()` had produced anything at all — the
    configuration only ever ran inside publish.yml with the production key, so a broken
    signing setup would first be reported by the Portal, after automaticRelease=true had
    already made the upload immutable.

    "Every non-checksum, non-metadata file" is exactly the set Gradle signs: `sign(publication)`
    covers the POM, the .module metadata and every jar artifact.
    """
    files = signable_files(base)
    if not rep.check(scope, bool(files), f"published files present ({len(files)})"):
        return
    for f in files:
        sig = f.with_name(f.name + ".asc")
        if not rep.check(scope, sig.is_file(), f"signature present ({sig.name})"):
            continue
        rep.check(scope, sig.read_bytes().lstrip().startswith(SIGNATURE_HEADER),
                  f"{sig.name} is an armored PGP signature")


def signing_is_active(root: Path) -> bool:
    """Whether this publish signed anything at all. Used only to resolve --signatures=auto."""
    return any(root.rglob("*.asc"))


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

    # Sources and javadoc jars are required, per artifact, by name.
    #
    # This used to be `base.glob(f"{artifact}-{version}*.jar")`, which reported *nothing* when
    # it matched nothing: if either jar stopped being produced, this script passed and the
    # Central Portal rejected the bundle. That is not hypothetical — gradle-plugin's build
    # file carries a live warning that calling withSourcesJar()/withJavadocJar() there crashes
    # publishing with duplicate artifacts, and the obvious "fix" for such a crash is to stop
    # producing the jar. vanniktech configures both automatically; their absence means
    # somebody turned them off.
    siblings = [jar]
    for classifier in ("sources", "javadoc"):
        extra = base / f"{artifact}-{version}-{classifier}.jar"
        if rep.check(scope, extra.is_file(), f"{classifier} jar present ({extra.name})"):
            siblings.append(extra)

    # A placeholder in any of them is still a shipped bug.
    for sibling in siblings:
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


def verify_plugin_marker(base: Path, version: str, rep: Report) -> None:
    """The coordinate `plugins { id("de.lhns.mcdp") }` actually resolves.

    Gradle resolves a plugin id to `<id>:<id>.gradle.plugin`, reads that POM, and follows its
    single dependency to the jar. Nothing in this repo writes that POM — `java-gradle-plugin`
    generates it — and nothing until now opened it. A marker that points at the wrong
    coordinate, or that silently stops being published, breaks every consumer's
    `plugins { }` block while the band jars and the gradle-plugin jar all look perfect.

    It is an alias publication: POM only, no jar, no .module, packaging `pom`.
    """
    scope = PLUGIN_MARKER_ARTIFACT
    pom = base / f"{PLUGIN_MARKER_ARTIFACT}-{version}.pom"
    if not rep.check(scope, pom.is_file(), f"marker pom present ({pom})"):
        return
    for tag, want in (("groupId", PLUGIN_MARKER_GROUP),
                      ("artifactId", PLUGIN_MARKER_ARTIFACT),
                      ("version", version),
                      ("packaging", "pom"),
                      ("name", PLUGIN_MARKER_POM_NAME)):
        got = pom_text(pom, tag)
        rep.check(scope, got == want, f"pom <{tag}> = {got!r} (want {want!r})")

    want_dep = (GROUP, PLUGIN_IMPL_ARTIFACT, version)
    deps = pom_dependencies(pom)
    rep.check(scope, deps == [want_dep],
              f"marker dependency = {deps} (want exactly [{want_dep}])")


def verify_band_list(settings: Path, rep: Report) -> None:
    """A new band added to settings.gradle.kts but not to BANDS would otherwise ship
    entirely unchecked."""
    declared = set(re.findall(r'include\("mcdp-([^"]+)"\)', settings.read_text(encoding="utf-8")))
    known = set(BANDS)
    rep.check("bands", declared == known,
              f"settings.gradle.kts bands {sorted(declared)} vs verified {sorted(known)}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Assert the release contract on published artifacts.")
    ap.add_argument("--version", required=True, help="Published version, e.g. 0.2.1-SNAPSHOT")
    ap.add_argument("--m2", default=str(Path.home() / ".m2" / "repository"),
                    help="Local Maven repository root")
    ap.add_argument("--settings", default="settings.gradle.kts",
                    help="settings.gradle.kts, cross-checked against the band table")
    ap.add_argument("--signatures", choices=("auto", "require", "skip"), default="auto",
                    help="'require': every published file must have an armored .asc beside "
                         "it — what the release dry run passes, so a signing configuration "
                         "that produces nothing fails here instead of at the Portal. "
                         "'auto' (default): require them only if this repository contains "
                         "any .asc at all, so an unsigned -SNAPSHOT publishToMavenLocal run "
                         "(which has no key and, being a snapshot, is not required to sign) "
                         "still passes. 'skip': never look. The blind spot in 'auto' is "
                         "deliberate and bounded: it can only miss signing being off "
                         "*everywhere*, which is precisely the case the dry run pins down "
                         "with 'require'.")
    args = ap.parse_args()

    root = Path(args.m2) / Path(*GROUP.split("."))
    rep = Report()

    signatures = args.signatures
    if signatures == "auto":
        signatures = "require" if signing_is_active(root) else "skip"

    print(f"repo: {root}\nversion: {args.version}")
    print(f"signatures: {args.signatures}"
          + (f" -> {signatures}" if args.signatures == "auto" else "")
          + ("" if signatures == "require"
             else "  (no .asc anywhere: signing is not active in this publish)"))
    print()
    verify_band_list(Path(args.settings), rep)

    # (directory, scope) pairs to sweep for signatures once the contents have been checked.
    signed_dirs: list[tuple[Path, str]] = []

    for band, spec in BANDS.items():
        artifact = f"mcdp-{band}"
        print(f"\n{artifact}")
        base = root / artifact / args.version
        verify_band(base, artifact, args.version, spec, rep)
        signed_dirs.append((base, artifact))

    for artifact, pom_name in EXTRA_ARTIFACTS.items():
        print(f"\n{artifact}")
        base = root / artifact / args.version
        verify_coordinates(base, artifact, args.version, pom_name, rep)
        signed_dirs.append((base, artifact))

    print(f"\n{PLUGIN_MARKER_ARTIFACT}")
    marker_base = (Path(args.m2) / Path(*PLUGIN_MARKER_GROUP.split("."))
                   / PLUGIN_MARKER_ARTIFACT / args.version)
    verify_plugin_marker(marker_base, args.version, rep)
    signed_dirs.append((marker_base, PLUGIN_MARKER_ARTIFACT))

    if signatures == "require":
        print("\nsignatures")
        for base, scope in signed_dirs:
            verify_signatures(base, scope, rep)

    print()
    if rep.failures:
        print(f"FAILED: {len(rep.failures)} assertion(s):")
        for f in rep.failures:
            print(f"  - {f}")
        return 1
    print(f"OK: {len(BANDS)} bands + {len(EXTRA_ARTIFACTS)} extra artifact(s) "
          f"+ the {PLUGIN_MARKER_ARTIFACT} marker verified"
          + (" (signed)." if signatures == "require" else ", signatures not checked."))
    return 0


if __name__ == "__main__":
    sys.exit(main())

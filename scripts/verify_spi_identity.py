#!/usr/bin/env python3
"""Verify the SPI-stability claims that let one mcdp band cover several loader versions.

Bands exist because a loader SPI broke. Where a band spans several loader versions, the
justification is always the same shape: "these classes are byte-identical across them, so one
compiled adapter is correct for all of them." That claim is load-bearing -- if a point release
changes one of those classes, the build stays green and the adapter NoSuchMethodErrors at boot --
and until this script it lived only in a comment in neoforge-26/build.gradle.kts.

Compares the SHA-256 of each named class entry across the listed loader jars. Network-bound, so
it runs as its own CI job rather than as a unit test.
"""
import argparse
import hashlib
import sys
import urllib.request
import zipfile
from io import BytesIO

MAVEN = "https://maven.neoforged.net/releases"

# (band, [loader versions it must cover], [class entries that must be identical across them])
CLAIMS = [
    (
        "mcdp-26 (ADR-0032)",
        # NeoForge 26.1.2.109, 26.2.0.88, 26.3.0.7-beta respectively.
        ["11.0.15", "11.0.16", "12.0.0"],
        [
            "net/neoforged/neoforgespi/language/IModLanguageLoader.class",
            "net/neoforged/fml/ModContainer.class",
            "net/neoforged/neoforgespi/language/IModInfo.class",
            "net/neoforged/neoforgespi/language/ModFileScanData.class",
        ],
    ),
]


def loader_jar(version):
    url = f"{MAVEN}/net/neoforged/fancymodloader/loader/{version}/loader-{version}.jar"
    with urllib.request.urlopen(url) as r:
        return url, zipfile.ZipFile(BytesIO(r.read()))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.parse_args()

    failures = []
    for band, versions, entries in CLAIMS:
        print(f"\n{band}: fancymodloader {', '.join(versions)}")
        jars = {}
        for v in versions:
            url, zf = loader_jar(v)
            jars[v] = zf
            print(f"  fetched {url}")
        for entry in entries:
            digests = {}
            for v, zf in jars.items():
                try:
                    digests[v] = hashlib.sha256(zf.read(entry)).hexdigest()
                except KeyError:
                    digests[v] = "ABSENT"
            unique = set(digests.values())
            short = {v: d[:12] for v, d in digests.items()}
            if len(unique) == 1 and "ABSENT" not in unique:
                print(f"  ok   {entry} -> {next(iter(unique))[:12]}")
            else:
                print(f"  FAIL {entry} -> {short}")
                failures.append(f"{band}: {entry} differs across {versions}: {short}")

    if failures:
        print(f"\nFAILED: {len(failures)} SPI identity claim(s) broken:")
        for f in failures:
            print(f"  - {f}")
        print("\nA band covering several loader versions is only correct while these match.")
        return 1
    print("\nOK: every SPI identity claim holds.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

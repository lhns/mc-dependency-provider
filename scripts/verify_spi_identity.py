#!/usr/bin/env python3
"""Verify the SPI-stability claims that let one mcdp band cover several loader versions.

Bands exist because a loader SPI broke. Where a band spans several loader versions, the
justification is always the same shape: "the SPI this adapter links against is the same across
them, so one compiled adapter is correct for all of them." That claim is load-bearing -- if a
point release changes one of those members, the build stays green and the adapter
NoSuchMethodErrors at boot.

What is compared is the adapter's actual linkage, not whole class files: every member the
adapter's bytecode references (name + descriptor + static-ness + the owner being a class or an
interface), plus, for each type the adapter implements or extends, the set of abstract methods
it must provide and the overridden methods that must stay non-final. A new method elsewhere in
ModContainer is not a break; a changed descriptor, a removed method, or a new abstract method on
IModLanguageLoader is.

Each claim is checked against its *baseline* -- the loader version the band compiles against --
because that is what the compiled adapter's references were resolved against. neoforge-26
compiles against fancymodloader 10.0.36 (its JDK 21 toolchain cannot read the 11.x/12.x jars)
and runs on 11.0.15 / 11.0.16 / 12.0.0.

Network-bound (maven.neoforged.net), so it runs as its own CI job rather than as a unit test.
`--jar VERSION=PATH` substitutes a local jar for a download, e.g. from ~/.gradle/caches.
"""
import argparse
import struct
import sys
import urllib.request
import zipfile
from io import BytesIO

MAVEN = "https://maven.neoforged.net/releases"

ACC_STATIC = 0x0008
ACC_FINAL = 0x0010
ACC_INTERFACE = 0x0200
ACC_ABSTRACT = 0x0400

SPI = "net/neoforged/neoforgespi"
FML = "net/neoforged/fml"

# What the FML-10 tree (neoforge-1.21.11/src, which neoforge-26 compiles) plus neoforge-shared
# link against in the loader jar. Keep in step with those sources: a new call there that is not
# listed here is simply not checked.
#
# ("member", owner, name, descriptor)  -- called/read; must exist with this descriptor and the
#                                         same static-ness, on an owner of the same kind
# ("class", owner)                     -- referenced as a type; must exist, same kind
# ("implements", owner, [overridden (name, descriptor), ...])
#                                      -- we implement/extend it: its abstract-method set must
#                                         not change, and what we override must stay non-final
FML10_ADAPTER_LINKAGE = [
    # McdpLanguageLoader
    ("implements", f"{SPI}/language/IModLanguageLoader", [
        ("name", "()Ljava/lang/String;"),
        ("version", "()Ljava/lang/String;"),
        ("loadMod", f"(L{SPI}/language/IModInfo;L{SPI}/language/ModFileScanData;"
                    f"Ljava/lang/ModuleLayer;)L{FML}/ModContainer;"),
    ]),
    ("member", f"{SPI}/language/IModLanguageLoader", "name", "()Ljava/lang/String;"),
    ("member", f"{SPI}/language/IModInfo", "getModId", "()Ljava/lang/String;"),
    ("member", f"{SPI}/language/IModInfo", "getOwningFile", f"()L{SPI}/language/IModFileInfo;"),
    ("member", f"{SPI}/language/IModInfo", "getLoader", f"()L{SPI}/language/IModLanguageLoader;"),
    ("member", f"{SPI}/language/IModFileInfo", "getFile", f"()L{SPI}/locating/IModFile;"),
    ("member", f"{SPI}/language/IModFileInfo", "getConfig", f"()L{SPI}/language/IConfigurable;"),
    ("member", f"{SPI}/locating/IModFile", "getContents", f"()L{FML}/jarcontents/JarContents;"),
    ("member", f"{SPI}/locating/IModFile", "getFilePath", "()Ljava/nio/file/Path;"),
    ("member", f"{FML}/jarcontents/JarContents", "containsFile", "(Ljava/lang/String;)Z"),
    ("member", f"{FML}/jarcontents/JarContents", "readFile", "(Ljava/lang/String;)[B"),
    ("member", f"{FML}/jarcontents/JarContents", "openFile",
     "(Ljava/lang/String;)Ljava/io/InputStream;"),
    ("member", f"{SPI}/language/IConfigurable", "getConfigList",
     "([Ljava/lang/String;)Ljava/util/List;"),
    ("member", f"{SPI}/language/IConfigurable", "getConfigElement",
     "([Ljava/lang/String;)Ljava/util/Optional;"),
    ("member", f"{FML}/loading/LoadingModList", "get", f"()L{FML}/loading/LoadingModList;"),
    ("member", f"{FML}/loading/LoadingModList", "getMods", "()Ljava/util/List;"),
    ("member", f"{SPI}/language/ModFileScanData", "getAnnotatedBy",
     "(Ljava/lang/Class;Ljava/lang/annotation/ElementType;)Ljava/util/stream/Stream;"),
    ("member", f"{SPI}/language/ModFileScanData$AnnotationData", "annotationData",
     "()Ljava/util/Map;"),
    ("member", f"{SPI}/language/ModFileScanData$AnnotationData", "clazz",
     "()Lorg/objectweb/asm/Type;"),
    ("class", f"{FML}/common/Mod"),
    # McdpModContainer
    ("implements", f"{FML}/ModContainer", [("constructMod", "()V")]),
    ("member", f"{FML}/ModContainer", "<init>", f"(L{SPI}/language/IModInfo;)V"),
    ("member", f"{FML}/ModContainer", "getModId", "()Ljava/lang/String;"),
    ("member", f"{FML}/ModContainer", "getEventBus", "()Lnet/neoforged/bus/api/IEventBus;"),
    ("member", f"{FML}/loading/FMLEnvironment", "getDist",
     "()Lnet/neoforged/api/distmarker/Dist;"),
    ("member", f"{FML}/javafmlmod/AutomaticEventSubscriber", "inject",
     f"(L{FML}/ModContainer;L{SPI}/language/ModFileScanData;Ljava/lang/Module;)V"),
    ("class", f"{FML}/event/IModBusEvent"),
    # neoforge-shared LoggingProgressListener -- reflective, by name, so a break here is silent
    ("member", f"{FML}/loading/progress/StartupNotificationManager", "addModMessage",
     "(Ljava/lang/String;)V"),
]

# (band, baseline the band compiles against, [loader versions it must run on], linkage)
CLAIMS = [
    (
        "mcdp-26 (ADR-0032)",
        "10.0.36",
        # NeoForge 26.1.2.109, 26.2.0.88, 26.3.0.7-beta respectively.
        ["11.0.15", "11.0.16", "12.0.0"],
        FML10_ADAPTER_LINKAGE,
    ),
]


# --- class-file reading ---------------------------------------------------------------------

class ClassInfo:
    def __init__(self, access, super_name, members):
        self.access = access
        self.super_name = super_name
        self.members = members  # (name, descriptor) -> access flags

    @property
    def is_interface(self):
        return bool(self.access & ACC_INTERFACE)


def parse_class(data):
    """Just enough of JVMS 4 to list a class's own fields and methods with their flags."""
    pos = 8  # magic, minor, major
    (cp_count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    utf8 = {}
    classes = {}
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            (length,) = struct.unpack_from(">H", data, pos)
            pos += 2
            utf8[i] = data[pos:pos + length].decode("utf-8", "replace")
            pos += length
        elif tag == 7:
            (classes[i],) = struct.unpack_from(">H", data, pos)
            pos += 2
        elif tag in (8, 16, 19, 20):
            pos += 2
        elif tag == 15:
            pos += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1  # 8-byte constants take two slots
        else:
            raise ValueError(f"unknown constant-pool tag {tag} at {pos - 1}")
        i += 1

    access, _this, super_idx, if_count = struct.unpack_from(">HHHH", data, pos)
    pos += 8 + 2 * if_count
    super_name = utf8[classes[super_idx]] if super_idx else None

    members = {}
    for _kind in ("fields", "methods"):
        (count,) = struct.unpack_from(">H", data, pos)
        pos += 2
        for _ in range(count):
            m_access, name_idx, desc_idx, attr_count = struct.unpack_from(">HHHH", data, pos)
            pos += 8
            for _ in range(attr_count):
                (attr_len,) = struct.unpack_from(">I", data, pos + 2)
                pos += 6 + attr_len
            members[(utf8[name_idx], utf8[desc_idx])] = m_access
    return ClassInfo(access, super_name, members)


class Loader:
    def __init__(self, version, zf):
        self.version = version
        self.zf = zf
        self.cache = {}

    def cls(self, internal_name):
        if internal_name not in self.cache:
            try:
                self.cache[internal_name] = parse_class(self.zf.read(internal_name + ".class"))
            except KeyError:
                self.cache[internal_name] = None
        return self.cache[internal_name]

    def abstract_methods(self, internal_name):
        """Own abstract methods, plus inherited ones for a class hierarchy inside this jar."""
        out = set()
        c = self.cls(internal_name)
        while c is not None:
            out |= {k for k, acc in c.members.items() if acc & ACC_ABSTRACT}
            c = self.cls(c.super_name) if c.super_name else None
        return out


def loader_jar(version, local):
    if version in local:
        return local[version], zipfile.ZipFile(local[version])
    url = f"{MAVEN}/net/neoforged/fancymodloader/loader/{version}/loader-{version}.jar"
    with urllib.request.urlopen(url) as r:
        return url, zipfile.ZipFile(BytesIO(r.read()))


# --- checks ---------------------------------------------------------------------------------

def check_member(base, other, owner, name, desc):
    b, o = base.cls(owner), other.cls(owner)
    if b is None:
        return f"{owner} is absent from the baseline {base.version} itself -- fix the claim"
    if (name, desc) not in b.members:
        return f"{owner}.{name}{desc} is absent from the baseline {base.version} -- fix the claim"
    if o is None:
        return f"{owner} is absent"
    if o.is_interface != b.is_interface:
        return f"{owner} changed kind (interface={b.is_interface} -> {o.is_interface})"
    if (name, desc) not in o.members:
        same_name = sorted(d for (n, d) in o.members if n == name)
        return f"{owner}.{name}{desc} is absent" + (f" (has {same_name})" if same_name else "")
    if (o.members[(name, desc)] & ACC_STATIC) != (b.members[(name, desc)] & ACC_STATIC):
        return f"{owner}.{name}{desc} changed static-ness"
    return None


def check_class(base, other, owner):
    b, o = base.cls(owner), other.cls(owner)
    if b is None:
        return f"{owner} is absent from the baseline {base.version} itself -- fix the claim"
    if o is None:
        return f"{owner} is absent"
    if o.is_interface != b.is_interface:
        return f"{owner} changed kind (interface={b.is_interface} -> {o.is_interface})"
    return None


def check_implements(base, other, owner, overridden):
    problem = check_class(base, other, owner)
    if problem:
        return problem
    b_abs, o_abs = base.abstract_methods(owner), other.abstract_methods(owner)
    if b_abs != o_abs:
        added = sorted(f"{n}{d}" for n, d in o_abs - b_abs)
        removed = sorted(f"{n}{d}" for n, d in b_abs - o_abs)
        return f"{owner} abstract methods changed: added {added}, no longer abstract {removed}"
    o = other.cls(owner)
    for name, desc in overridden:
        acc = o.members.get((name, desc))
        if acc is None:
            return f"{owner}.{name}{desc}, which the adapter overrides, is absent"
        if acc & ACC_FINAL:
            return f"{owner}.{name}{desc}, which the adapter overrides, is now final"
    return None


def describe(check):
    if check[0] == "member":
        return f"{check[1]}.{check[2]}{check[3]}"
    if check[0] == "class":
        return check[1]
    return f"{check[1]} (implemented/extended)"


def run_check(base, other, check):
    if check[0] == "member":
        return check_member(base, other, check[1], check[2], check[3])
    if check[0] == "class":
        return check_class(base, other, check[1])
    return check_implements(base, other, check[1], check[2])


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jar", action="append", default=[], metavar="VERSION=PATH",
                    help="use a local loader jar for VERSION instead of downloading it")
    args = ap.parse_args()
    local = dict(spec.split("=", 1) for spec in args.jar)

    failures = []
    for band, baseline, versions, linkage in CLAIMS:
        print(f"\n{band}: baseline fancymodloader {baseline}, runs on {', '.join(versions)}")
        src, zf = loader_jar(baseline, local)
        print(f"  fetched {src}")
        base = Loader(baseline, zf)
        # Self-check first: a claim naming something the baseline lacks is a broken claim, and
        # would otherwise read as a break in every version.
        for check in linkage:
            problem = run_check(base, base, check)
            if problem:
                failures.append(f"{band}: {problem}")
                print(f"  FAIL claim {describe(check)}: {problem}")
        for v in versions:
            src, zf = loader_jar(v, local)
            print(f"  fetched {src}")
            other = Loader(v, zf)
            broken = 0
            for check in linkage:
                problem = run_check(base, other, check)
                if problem:
                    broken += 1
                    failures.append(f"{band}: {v}: {problem}")
                    print(f"  FAIL {v}: {problem}")
            if not broken:
                print(f"  ok   {v}: all {len(linkage)} linkage checks match {baseline}")

    if failures:
        print(f"\nFAILED: {len(failures)} SPI linkage claim(s) broken:")
        for f in failures:
            print(f"  - {f}")
        print("\nA band covering several loader versions is only correct while its adapter's "
              "linkage matches the version it compiles against.")
        return 1
    print("\nOK: every SPI linkage claim holds.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

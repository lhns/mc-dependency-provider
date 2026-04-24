package io.github.mclibprovider.core;

import io.github.mclibprovider.deps.Manifest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Version-aware coalescing for stdlib-style libraries (task #33 / open-question #1).
 *
 * Pure SHA-keyed coalescing (ADR-0006) shares a library jar only when two mods pin the
 * <em>exact same</em> SHA-256. If twenty mods each pin {@code scala3-library_3:3.5.2} that's
 * one shared loader; if they pin {@code 3.5.2} vs {@code 3.5.3} it's twenty. For stdlib
 * jars that's wasteful — all twenty copies of {@code scala.Option} behave identically as
 * far as any mod is concerned, and they don't inter-operate across mods anyway (each mod's
 * own classes link to its own stdlib class).
 *
 * This policy takes a set of <em>promoted coordinate stems</em> ({@code groupId:artifactId})
 * and picks, across the union of all mods' manifests, the single highest version per stem.
 * Platform adapters that opt in call {@link #selectPromotions(Collection)} before mod
 * registration, build one shared loader per selected library, and strip the promoted libs
 * from each mod's per-mod URL list — the shared loader becomes part of the parent chain
 * instead. Non-promoted libraries keep SHA-keyed per-version loaders.
 *
 * Opt-in by design: the default {@link LoaderCoordinator#register} path is unchanged so a
 * misconfigured promotion set can't silently break an existing install.
 */
public final class StdlibPromotion {

    /**
     * Default set covering the usual suspects: Scala 3, Scala 2, Kotlin stdlib (both the
     * plain and JVM-specific variants). These are the libraries most likely to be pinned
     * across many mods with minor-version drift.
     */
    public static final Set<String> DEFAULT_PROMOTED_STEMS = Set.of(
            "org.scala-lang:scala3-library_3",
            "org.scala-lang:scala-library",
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8");

    private final Set<String> promotedStems;

    public StdlibPromotion(Set<String> promotedStems) {
        this.promotedStems = Set.copyOf(promotedStems);
    }

    public static StdlibPromotion defaults() {
        return new StdlibPromotion(DEFAULT_PROMOTED_STEMS);
    }

    public Set<String> promotedStems() {
        return promotedStems;
    }

    /**
     * Given every mod's manifest, pick exactly one {@link Manifest.Library} per promoted
     * coordinate stem — the one with the highest version. Returns a map keyed by stem.
     * Mods that don't reference a promoted stem contribute nothing.
     */
    public Map<String, Manifest.Library> selectPromotions(Collection<Manifest> manifests) {
        Map<String, Manifest.Library> best = new LinkedHashMap<>();
        for (Manifest m : manifests) {
            for (Manifest.Library lib : m.libraries()) {
                String stem = stemOf(lib.coords());
                if (!promotedStems.contains(stem)) continue;
                Manifest.Library current = best.get(stem);
                if (current == null || compareVersions(versionOf(lib.coords()), versionOf(current.coords())) > 0) {
                    best.put(stem, lib);
                }
            }
        }
        return best;
    }

    /**
     * Return the mod's library list with any library in {@code selected} removed. Order of
     * the surviving libraries is preserved. Used by adapters after {@link #selectPromotions}
     * to strip promoted libs from each mod's per-mod URL list.
     */
    public List<Manifest.Library> stripPromoted(Manifest m, Map<String, Manifest.Library> selected) {
        List<Manifest.Library> out = new ArrayList<>(m.libraries().size());
        for (Manifest.Library lib : m.libraries()) {
            String stem = stemOf(lib.coords());
            if (!selected.containsKey(stem)) out.add(lib);
        }
        return out;
    }

    static String stemOf(String coords) {
        int firstColon = coords.indexOf(':');
        int secondColon = coords.indexOf(':', firstColon + 1);
        if (firstColon < 0 || secondColon < 0) {
            throw new IllegalArgumentException("expected groupId:artifactId:version, got: " + coords);
        }
        return coords.substring(0, secondColon);
    }

    static String versionOf(String coords) {
        int secondColon = coords.indexOf(':', coords.indexOf(':') + 1);
        if (secondColon < 0) return "";
        return coords.substring(secondColon + 1);
    }

    /**
     * Compare two Maven version strings. Numeric components compared numerically; non-numeric
     * tails ordered as "release > milestone > rc > beta > alpha > snapshot". Good enough for
     * stdlib-style version-bumps; not a full Maven resolver, not a strict semver spec.
     */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "";
            String sb = i < pb.length ? pb[i] : "";
            int cmp = comparePart(sa, sb);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static int comparePart(String a, String b) {
        boolean na = a.matches("\\d+"), nb = b.matches("\\d+");
        if (na && nb) return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        if (na) return 1;   // numeric > non-numeric qualifier ("3.5.2" > "3.5-alpha")
        if (nb) return -1;
        return Integer.compare(qualifierRank(a.toLowerCase()), qualifierRank(b.toLowerCase()));
    }

    private static int qualifierRank(String q) {
        if (q.isEmpty()) return 5;
        if (q.equals("ga") || q.equals("final") || q.equals("release")) return 5;
        if (q.startsWith("rc")) return 4;
        if (q.startsWith("m") || q.contains("milestone")) return 3;
        if (q.startsWith("beta")) return 2;
        if (q.startsWith("alpha")) return 1;
        if (q.equals("snapshot")) return 0;
        return 5; // unknown qualifier: treat as release
    }
}

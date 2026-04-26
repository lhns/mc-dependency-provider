package de.lhns.mcdp.deps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Opt-in URL-prefix whitelist enforced before any HTTP download (task #33 /
 * open-question #3: supply-chain hardening).
 *
 * When constructed with a non-empty prefix list, {@link ManifestConsumer}
 * rejects any library whose URL does not start with one of the prefixes —
 * fails loudly before a request is sent, so a tampered manifest can't exfiltrate
 * to an attacker-controlled repo even if the SHA-256 check would later catch it.
 *
 * Default behavior (when no whitelist is constructed): no enforcement. Adoption
 * is opt-in to avoid breaking existing installs; modpack authors and paranoid
 * deployments can set {@code MCDP_REPO_WHITELIST} to a
 * comma-separated list of prefixes ({@link #fromEnv()}).
 */
public final class RepoWhitelist {

    public static final String ENV_VAR = "MCDP_REPO_WHITELIST";

    /** Canonical Maven Central prefixes — the usual choice when enabling the whitelist. */
    public static final List<String> MAVEN_CENTRAL_PREFIXES = List.of(
            "https://repo1.maven.org/maven2/",
            "https://repo.maven.apache.org/maven2/");

    private final List<String> prefixes;

    public RepoWhitelist(List<String> prefixes) {
        this.prefixes = List.copyOf(Objects.requireNonNull(prefixes, "prefixes"));
    }

    /**
     * Construct from {@link #ENV_VAR} if set and non-blank; otherwise returns {@code null}
     * meaning no enforcement. Callers opt in by checking for null.
     */
    public static RepoWhitelist fromEnv() {
        String raw = System.getenv(ENV_VAR);
        if (raw == null || raw.isBlank()) return null;
        List<String> parts = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        return parts.isEmpty() ? null : new RepoWhitelist(parts);
    }

    public List<String> prefixes() {
        return prefixes;
    }

    public boolean allows(String url) {
        for (String p : prefixes) {
            if (url.startsWith(p)) return true;
        }
        return false;
    }

    /** Throws {@link java.io.IOException}-ready exception-cause with a clear diagnostic. */
    public String rejectionMessage(String url) {
        return "URL not in repo whitelist: " + url + " — allowed prefixes: " + prefixes;
    }
}

package de.lhns.mcdp.core;

import de.lhns.mcdp.api.McdpProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Helpers for platform adapters to map every Mixin class listed in a mod's Mixin config JSON to
 * its owning mod, via {@link McdpProvider#registerMixinOwner}. Defense-in-depth complement to the
 * recommended {@code @McdpMixin(modId = "...")} callsite form (ADR-0008) — a mod author who
 * forgets to set {@code modId} still gets the right loader under multi-mod configurations as long
 * as their Mixin config is discoverable.
 *
 * <p>Mixin's JSON config file shape (SpongePowered Mixin 0.8.x, used by both FabricMC and NeoForge):
 * <pre>{@code
 * {
 *   "package": "com.example.mixin",
 *   "mixins":  ["FooMixin"],     // common; applied on both sides
 *   "client":  ["BarClient"],    // client-only
 *   "server":  ["BazServer"],    // server-only
 *   "refmap":  "..."             // irrelevant here
 * }
 * }</pre>
 *
 * FQN = {@code package + "." + className}. All three class-array fields are merged because we
 * want to register owners regardless of side.
 */
public final class MixinConfigScanner {

    private MixinConfigScanner() {}

    /**
     * Given a mod's root {@link Path} views (may be a jar-fs root or dev-mode source-set dirs)
     * and a list of mixin config resource paths (each relative to the mod root,
     * e.g. {@code "fabric-example.mixins.json"}), read each config and register every listed class
     * FQN as owned by {@code modId}.
     *
     * <p>Silent on per-config read/parse failure — this is a best-effort registration path. The
     * annotation-{@code modId} route (ADR-0008 path 1) is the primary guarantee; this scan exists
     * only to paper over mods that don't use it.
     *
     * @return the list of registered FQNs (for logging / tests)
     */
    public static List<String> registerMixinOwnersFromConfigs(String modId,
                                                              List<Path> modRoots,
                                                              List<String> configRelativePaths) {
        List<String> registered = new ArrayList<>();
        for (String cfg : configRelativePaths) {
            Path resolved = resolveInRoots(modRoots, cfg);
            if (resolved == null || !Files.exists(resolved)) continue;
            try (InputStream in = Files.newInputStream(resolved)) {
                String text = new String(in.readAllBytes());
                List<String> fqns = extractFqnsFromConfig(text);
                for (String fqn : fqns) {
                    McdpProvider.registerMixinOwner(fqn, modId);
                    registered.add(fqn);
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // best-effort; annotation-modId path still works.
                // IOException: read failure. IllegalArgumentException: MiniJson parse error.
            }
        }
        return registered;
    }

    /**
     * Parse a Mixin config JSON and return the fully-qualified names of every class declared under
     * {@code mixins}, {@code client}, and {@code server}.
     */
    public static List<String> extractFqnsFromConfig(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map<?, ?> m)) return List.of();
        Object pkgObj = m.get("package");
        if (!(pkgObj instanceof String pkg) || pkg.isBlank()) return List.of();

        List<String> out = new ArrayList<>();
        for (String field : new String[]{"mixins", "client", "server"}) {
            Object arr = m.get(field);
            if (!(arr instanceof List<?> list)) continue;
            for (Object e : list) {
                if (e instanceof String name && !name.isBlank()) {
                    out.add(pkg + "." + name);
                }
            }
        }
        return out;
    }

    /**
     * Parse {@code fabric.mod.json}'s {@code mixins} field and return the list of config file
     * names. Handles all three documented shapes: a single string, an array of strings, or an
     * array of objects with a {@code config} property (environment is ignored — we register
     * owners for all sides).
     */
    public static List<String> parseFabricMixinConfigs(String fabricModJson) {
        Object root = MiniJson.parse(fabricModJson);
        if (!(root instanceof Map<?, ?> m)) return List.of();
        Object v = m.get("mixins");
        if (v == null) return List.of();
        List<String> out = new ArrayList<>();
        if (v instanceof String s && !s.isBlank()) {
            out.add(s);
        } else if (v instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof String s2 && !s2.isBlank()) {
                    out.add(s2);
                } else if (e instanceof Map<?, ?> obj) {
                    Object cfg = obj.get("config");
                    if (cfg instanceof String s3 && !s3.isBlank()) out.add(s3);
                }
            }
        }
        return out;
    }

    private static Path resolveInRoots(List<Path> roots, String relative) {
        for (Path r : roots) {
            Path candidate;
            try {
                candidate = r.resolve(relative);
            } catch (RuntimeException e) {
                continue;
            }
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }
}

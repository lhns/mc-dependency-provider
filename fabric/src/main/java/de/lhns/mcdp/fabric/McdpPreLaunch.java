package de.lhns.mcdp.fabric;

import de.lhns.mcdp.api.McdpProvider;
import de.lhns.mcdp.core.DevRootSafetyNet;
import de.lhns.mcdp.core.LoaderCoordinator;
import de.lhns.mcdp.core.MixinConfigScanner;
import de.lhns.mcdp.core.ModClassLoader;
import de.lhns.mcdp.core.StdlibPromotion;
import de.lhns.mcdp.deps.LibraryCache;
import de.lhns.mcdp.deps.Manifest;
import de.lhns.mcdp.deps.ManifestConsumer;
import de.lhns.mcdp.deps.ManifestIo;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fabric pre-launch bootstrap. Runs before any {@link McdpLanguageAdapter#create} invocation.
 * <p>
 * For every mod carrying {@code META-INF/mcdepprovider.toml}, this:
 * <ol>
 *   <li>parses the manifest;</li>
 *   <li>downloads + SHA-verifies every listed library into the shared cache;</li>
 *   <li>builds a per-mod {@link ModClassLoader} via the coordinator;</li>
 *   <li>registers it with {@link McdpProvider} so the language adapter and Mixin bridge can see it.</li>
 * </ol>
 */
public final class McdpPreLaunch implements PreLaunchEntrypoint {

    private static final String MANIFEST_PATH = "META-INF/mcdepprovider.toml";

    private static final Logger LOG = LoggerFactory.getLogger("mcdepprovider");

    private static final LoaderCoordinator COORDINATOR =
            new LoaderCoordinator(McdpPreLaunch.class.getClassLoader());

    private static final Map<String, String> LANG_BY_MOD = new HashMap<>();

    @Override
    public void onPreLaunch() {
        FabricLoader fabric = FabricLoader.getInstance();
        LibraryCache cache = LibraryCache.defaultCache();
        ManifestConsumer consumer = new ManifestConsumer(cache);

        // Two-pass: collect every mod's manifest + resolved libs first so StdlibPromotion can
        // see the full union before we build any classloader. Adding mods in a single loop
        // would force stdlib-version decisions per-mod, undoing cross-mod promotion.
        List<ModEntry> entries = new ArrayList<>();
        for (ModContainer mod : fabric.getAllMods()) {
            Optional<Path> manifestPath = mod.findPath(MANIFEST_PATH);
            if (manifestPath.isEmpty()) continue;

            String modId = mod.getMetadata().getId();
            Manifest manifest;
            try (InputStream in = Files.newInputStream(manifestPath.get())) {
                manifest = ManifestIo.read(in);
            } catch (IOException e) {
                throw new IllegalStateException("mcdepprovider: failed to read manifest for " + modId, e);
            }

            List<Path> libs;
            try {
                libs = consumer.resolveAll(manifest);
            } catch (IOException e) {
                throw new IllegalStateException("mcdepprovider: failed to download libs for " + modId, e);
            }

            // Source-set output dirs the manifest captured at build time. In a shipped jar this
            // list is either absent or contains absolute paths to the build machine that don't
            // exist on the consumer's filesystem; we filter those out and fall back to the
            // loader's reported rootPaths.
            List<Path> manifestDevRoots = manifest.devRoots().stream()
                    .map(Path::of)
                    .filter(Files::isDirectory)
                    .toList();
            List<Path> modPaths;
            if (!manifestDevRoots.isEmpty()) {
                modPaths = manifestDevRoots;
            } else if (!mod.getRootPaths().isEmpty()) {
                modPaths = mod.getRootPaths();
            } else {
                modPaths = List.of(manifestPath.get().getFileSystem().getPath(""));
            }
            // Safety net: if the bridge codegen output dir isn't already on modPaths but is
            // reachable from one of them on disk, append it. Catches consumers whose manifest
            // dev_roots was emitted by an older plugin or whose Loom run re-rooted the source
            // set after our plugin's main.output.dir(...) registration. Without this, bridge
            // impls fall through to KnotClassLoader.
            modPaths = DevRootSafetyNet.appendBridgeClassesIfPresent(modPaths);

            entries.add(new ModEntry(modId, manifest, libs, modPaths));
        }

        StdlibPromotion policy = StdlibPromotion.defaults();
        List<Manifest> allManifests = new ArrayList<>(entries.size());
        for (ModEntry e : entries) allManifests.add(e.manifest);
        Map<String, Manifest.Library> selected = policy.selectPromotions(allManifests);

        java.net.URLClassLoader promoted = null;
        if (!selected.isEmpty()) {
            List<Path> promotedJars = new ArrayList<>(selected.size());
            List<String> promotedShas = new ArrayList<>(selected.size());
            for (Manifest.Library lib : selected.values()) {
                try {
                    promotedJars.add(consumer.resolve(lib));
                } catch (IOException e) {
                    throw new IllegalStateException("mcdepprovider: failed to resolve promoted lib " + lib.coords(), e);
                }
                promotedShas.add(lib.sha256());
            }
            promoted = COORDINATOR.buildSharedLibraryLoader(promotedJars, promotedShas);
        }

        for (ModEntry e : entries) {
            Manifest reducedManifest = new Manifest(
                    e.manifest.lang(),
                    e.manifest.sharedPackages(),
                    policy.stripPromoted(e.manifest, selected));
            List<Path> reducedLibs = filterNonPromoted(e.manifest, e.libs, selected);
            ClassLoader libParent = promoted != null ? promoted : McdpPreLaunch.class.getClassLoader();
            ModClassLoader loader = COORDINATOR.register(
                    e.modId, reducedManifest, e.modPaths, reducedLibs, libParent);
            McdpProvider.registerMod(e.modId, loader);
            // Bridge-manifest discovery: single TOML file per mod (ADR-0019). Single-file
            // findPath is reliable across Fabric dev/prod. Three-line diagnostic mirrors the
            // NeoForge adapter; see McdpLanguageLoader for the rationale.
            Path manifestToml = fabric.getModContainer(e.modId)
                    .flatMap(mc -> mc.findPath("META-INF/mcdp-bridges.toml"))
                    .orElse(null);
            if (manifestToml == null) {
                LOG.info("mcdepprovider: no auto-bridge manifest file for {} "
                        + "(mod has no mixins, or findPath returned empty)", e.modId);
            } else {
                LOG.info("mcdepprovider: scanning {} for {} (exists={}, regular={})",
                        manifestToml, e.modId,
                        Files.exists(manifestToml), Files.isRegularFile(manifestToml));
                int registered = McdpProvider.registerAutoBridgeManifestToml(loader, manifestToml);
                LOG.info("mcdepprovider: registered {} auto-bridge entries for {}", registered, e.modId);
            }
            LANG_BY_MOD.put(e.modId, e.manifest.lang());
            registerMixinOwnersForFabricMod(e);
        }
    }

    /**
     * Best-effort pre-registration of mixin class FQNs → owning modId so
     * {@link McdpProvider#loadMixinImpl} can route correctly under multi-mod configurations
     * without relying on {@code @McdpMixin(modId = "...")} at the callsite. See ADR-0008 path 2.
     * Silent on any parse/read failure — the annotation path remains the primary guarantee.
     */
    private static void registerMixinOwnersForFabricMod(ModEntry e) {
        try {
            Path fmj = null;
            for (Path r : e.modPaths) {
                Path c = r.resolve("fabric.mod.json");
                if (Files.exists(c)) { fmj = c; break; }
            }
            if (fmj == null) return;
            String text = Files.readString(fmj);
            List<String> configs = MixinConfigScanner.parseFabricMixinConfigs(text);
            if (configs.isEmpty()) return;
            MixinConfigScanner.registerMixinOwnersFromConfigs(e.modId, e.modPaths, configs);
        } catch (IOException | IllegalArgumentException ignored) {
            // IOException: fabric.mod.json read failure. IllegalArgumentException: MiniJson parse error.
        }
    }

    private static List<Path> filterNonPromoted(Manifest m, List<Path> libs,
                                                Map<String, Manifest.Library> selected) {
        List<Path> out = new ArrayList<>(libs.size());
        List<Manifest.Library> declared = m.libraries();
        for (int i = 0; i < declared.size(); i++) {
            String stem = StdlibPromotion.stemOf(declared.get(i).coords());
            if (!selected.containsKey(stem)) out.add(libs.get(i));
        }
        return out;
    }

    private record ModEntry(String modId, Manifest manifest, List<Path> libs, List<Path> modPaths) {}

    /** Used by {@link McdpLanguageAdapter} to pick the right {@code EntrypointAdapter}. */
    static String langFor(String modId) {
        return LANG_BY_MOD.getOrDefault(modId, "java");
    }

}

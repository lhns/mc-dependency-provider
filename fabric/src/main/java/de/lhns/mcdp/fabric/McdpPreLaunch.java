package de.lhns.mcdp.fabric;

import de.lhns.mcdp.api.McdpProvider;
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

            List<Path> modPaths = mod.getRootPaths().isEmpty()
                    ? List.of(manifestPath.get().getFileSystem().getPath(""))
                    : expandDevRoots(mod.getRootPaths());

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
            // Defense-in-depth: even though Fabric's expanded modPaths usually cover the bridge
            // manifest dir, explicitly look it up via Fabric's unified mod-content view so we're
            // robust against future changes to which roots show up on the per-mod loader.
            Optional<Path> bridgeManifestDir = fabric.getModContainer(e.modId)
                    .flatMap(mc -> mc.findPath("META-INF/mcdp-mixin-bridges"));
            bridgeManifestDir.ifPresent(p -> McdpProvider.registerAutoBridgeManifests(loader, p));
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
        } catch (IOException | RuntimeException ignored) {
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

    // Dev-mode only: Loom exposes a mod's build/resources/main directory as the sole rootPath,
    // not the sibling build/classes/<lang>/main directories. Without the compiled-class dirs on
    // our ModClassLoader's URL list, Class.forName falls through to Knot, which defeats isolation.
    // Walk from each rootPath to its siblings and add any that exist.
    private static List<Path> expandDevRoots(List<Path> roots) {
        List<Path> out = new ArrayList<>(roots.size() * 2);
        for (Path p : roots) {
            out.add(p);
            if (p.getFileSystem() != java.nio.file.FileSystems.getDefault()) continue;
            Path fileName = p.getFileName();
            Path parent = p.getParent();
            if (fileName == null || parent == null) continue;
            if (!"main".equals(fileName.toString())) continue;
            // parent = <project>/build/resources, grand = <project>/build
            Path grand = parent.getParent();
            if (grand == null) continue;
            Path classes = grand.resolve("classes");
            if (!java.nio.file.Files.isDirectory(classes)) continue;
            try (var stream = java.nio.file.Files.newDirectoryStream(classes)) {
                for (Path lang : stream) {
                    Path candidate = lang.resolve("main");
                    if (java.nio.file.Files.isDirectory(candidate) && !out.contains(candidate)) {
                        out.add(candidate);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return List.copyOf(out);
    }
}

package io.github.mclibprovider.fabric;

import io.github.mclibprovider.api.McLibProvider;
import io.github.mclibprovider.core.LoaderCoordinator;
import io.github.mclibprovider.core.ModClassLoader;
import io.github.mclibprovider.deps.LibraryCache;
import io.github.mclibprovider.deps.Manifest;
import io.github.mclibprovider.deps.ManifestConsumer;
import io.github.mclibprovider.deps.ManifestIo;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fabric pre-launch bootstrap. Runs before any {@link McLibLanguageAdapter#create} invocation.
 * <p>
 * For every mod carrying {@code META-INF/mc-jvm-mod.toml}, this:
 * <ol>
 *   <li>parses the manifest;</li>
 *   <li>downloads + SHA-verifies every listed library into the shared cache;</li>
 *   <li>builds a per-mod {@link ModClassLoader} via the coordinator;</li>
 *   <li>registers it with {@link McLibProvider} so the language adapter and Mixin bridge can see it.</li>
 * </ol>
 */
public final class McLibPreLaunch implements PreLaunchEntrypoint {

    private static final String MANIFEST_PATH = "META-INF/mc-jvm-mod.toml";

    private static final LoaderCoordinator COORDINATOR =
            new LoaderCoordinator(McLibPreLaunch.class.getClassLoader());

    private static final Map<String, String> LANG_BY_MOD = new HashMap<>();

    @Override
    public void onPreLaunch() {
        FabricLoader fabric = FabricLoader.getInstance();
        LibraryCache cache = LibraryCache.defaultCache();
        ManifestConsumer consumer = new ManifestConsumer(cache);

        for (ModContainer mod : fabric.getAllMods()) {
            Optional<Path> manifestPath = mod.findPath(MANIFEST_PATH);
            if (manifestPath.isEmpty()) continue;

            String modId = mod.getMetadata().getId();
            Manifest manifest;
            try (InputStream in = Files.newInputStream(manifestPath.get())) {
                manifest = ManifestIo.read(in);
            } catch (IOException e) {
                throw new IllegalStateException("mc-lib-provider: failed to read manifest for " + modId, e);
            }

            List<Path> libs;
            try {
                libs = consumer.resolveAll(manifest);
            } catch (IOException e) {
                throw new IllegalStateException("mc-lib-provider: failed to download libs for " + modId, e);
            }

            Path modJar = mod.getRootPaths().isEmpty()
                    ? manifestPath.get().getFileSystem().getPath("")
                    : mod.getRootPaths().get(0);

            ModClassLoader loader = COORDINATOR.register(modId, manifest, modJar, libs);
            McLibProvider.registerMod(modId, loader);
            LANG_BY_MOD.put(modId, manifest.lang());
        }
    }

    /** Used by {@link McLibLanguageAdapter} to pick the right {@code EntrypointAdapter}. */
    static String langFor(String modId) {
        return LANG_BY_MOD.getOrDefault(modId, "java");
    }
}

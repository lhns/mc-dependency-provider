package io.github.mclibprovider.neoforge;

import io.github.mclibprovider.api.McLibProvider;
import io.github.mclibprovider.core.EntrypointAdapter;
import io.github.mclibprovider.core.LoaderCoordinator;
import io.github.mclibprovider.core.ModClassLoader;
import io.github.mclibprovider.deps.LibraryCache;
import io.github.mclibprovider.deps.Manifest;
import io.github.mclibprovider.deps.ManifestConsumer;
import io.github.mclibprovider.deps.ManifestIo;
import net.neoforged.neoforgespi.language.ILifecycleEvent;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageProvider;
import net.neoforged.neoforgespi.language.ModFileScanData;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * NeoForge entry. Registered via
 * {@code META-INF/services/net.neoforged.neoforgespi.language.IModLanguageProvider}.
 * <p>
 * Flow:
 * <ol>
 *   <li>NeoForge calls {@link #getFileVisitor()} once per mod file scan.</li>
 *   <li>For any mod file whose mods.toml declares {@code modLoader = "mclibprovider"}, our visitor
 *       constructs a per-mod {@link McLibModLoader} and registers it on the scan data keyed by
 *       mod id.</li>
 *   <li>Later NeoForge invokes {@link McLibModLoader#loadMod(IModInfo, ModFileScanData, ModuleLayer)}
 *       to instantiate each mod — that's where we download libs, build the {@link ModClassLoader},
 *       and delegate to the language-specific {@link EntrypointAdapter}.</li>
 * </ol>
 * <p>
 * <b>NeoForge 21.x note.</b> This compiles against standalone {@code neoforgespi:9.0.2} for the
 * interface surface and against the bundled {@code fancymodloader:loader:4.0.42} /
 * {@code net.neoforged:bus:8.0.5} (as {@code compileOnly}) for {@link McLibModContainer}.
 * Both artifact versions align with NeoForge 21.1.x for Minecraft 1.21.1+; the SPI has been merged
 * into the main {@code net.neoforged:neoforge} distribution at runtime but the class shapes
 * referenced here are stable.
 */
public final class McLibLanguageLoader implements IModLanguageProvider {

    public static final String LANGUAGE_ID = "mclibprovider";
    private static final String MANIFEST_PATH = "META-INF/mc-jvm-mod.toml";

    private static final LoaderCoordinator COORDINATOR =
            new LoaderCoordinator(McLibLanguageLoader.class.getClassLoader());

    private static final LibraryCache CACHE = LibraryCache.defaultCache();
    private static final ManifestConsumer CONSUMER = new ManifestConsumer(CACHE);

    @Override
    public String name() {
        return LANGUAGE_ID;
    }

    @Override
    public Consumer<ModFileScanData> getFileVisitor() {
        return scanData -> {
            Map<String, IModLanguageLoader> targets = new LinkedHashMap<>();
            for (IModFileInfo fileInfo : scanData.getIModInfoData()) {
                boolean ours = fileInfo.requiredLanguageLoaders().stream()
                        .anyMatch(spec -> LANGUAGE_ID.equals(spec.languageName()));
                if (!ours) continue;

                for (IModInfo modInfo : fileInfo.getMods()) {
                    targets.put(modInfo.getModId(), new McLibModLoader(modInfo));
                }
            }
            if (!targets.isEmpty()) {
                scanData.addLanguageLoader(targets);
            }
        };
    }

    @Override
    public <R extends ILifecycleEvent<R>> void consumeLifecycleEvent(Supplier<R> event) {
        // No-op — our per-mod boot work all happens inside loadMod.
    }

    /**
     * One instance per discovered mod. NeoForge calls {@link #loadMod} after the scan phase.
     */
    static final class McLibModLoader implements IModLanguageLoader {

        McLibModLoader(IModInfo modInfo) {
            // Retained constructor param for future use (e.g. carrying scan-phase context);
            // loadMod receives a fresh IModInfo so we don't store it here.
        }

        @Override
        public <T> T loadMod(IModInfo info, ModFileScanData scanResults, ModuleLayer gameLayer) {
            String modId = info.getModId();
            Path modFile = info.getOwningFile().getFile().getFilePath();

            Manifest manifest = readManifest(modFile, modId);
            List<Path> libs = downloadLibs(manifest, modId);

            ModClassLoader loader = COORDINATOR.register(modId, manifest, modFile, libs);
            McLibProvider.registerMod(modId, loader);

            Class<?> entryClass = loadEntryClass(info, loader, modId);
            Object instance = construct(manifest.lang(), entryClass, info, modId);

            // NeoForge casts the return to net.neoforged.fml.ModContainer. Wrap our instance in
            // McLibModContainer so the cast succeeds and the event bus is wired.
            @SuppressWarnings("unchecked")
            T container = (T) new McLibModContainer(info, instance);
            return container;
        }

        private static Manifest readManifest(Path modFile, String modId) {
            try (var fs = java.nio.file.FileSystems.newFileSystem(modFile, (ClassLoader) null);
                 InputStream in = Files.newInputStream(fs.getPath(MANIFEST_PATH))) {
                return ManifestIo.read(in);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "mc-lib-provider: " + modId + " is missing or has a corrupt " + MANIFEST_PATH, e);
            }
        }

        private static List<Path> downloadLibs(Manifest manifest, String modId) {
            try {
                return CONSUMER.resolveAll(manifest);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "mc-lib-provider: failed to resolve libraries for " + modId, e);
            }
        }

        private static Class<?> loadEntryClass(IModInfo info, ModClassLoader loader, String modId) {
            Object entryProp = info.getModProperties().get("entrypoint");
            if (!(entryProp instanceof String entry) || entry.isBlank()) {
                throw new IllegalStateException("mc-lib-provider: mod " + modId
                        + " must declare `entrypoint = \"fully.qualified.ClassName\"` under its [[mods]] block");
            }
            try {
                return Class.forName(entry, true, loader);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "mc-lib-provider: entrypoint class not found for " + modId + ": " + entry, e);
            }
        }

        private static Object construct(String lang, Class<?> entryClass, IModInfo info, String modId) {
            try {
                return EntrypointAdapter.forLang(lang).construct(entryClass, info);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "mc-lib-provider: failed to instantiate entrypoint for " + modId, e);
            }
        }
    }
}

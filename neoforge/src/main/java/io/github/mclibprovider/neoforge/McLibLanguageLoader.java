package io.github.mclibprovider.neoforge;

import io.github.mclibprovider.api.McLibProvider;
import io.github.mclibprovider.core.EntrypointAdapter;
import io.github.mclibprovider.core.LoaderCoordinator;
import io.github.mclibprovider.core.ModClassLoader;
import io.github.mclibprovider.deps.LibraryCache;
import io.github.mclibprovider.deps.Manifest;
import io.github.mclibprovider.deps.ManifestConsumer;
import io.github.mclibprovider.deps.ManifestIo;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageLoader;
import net.neoforged.neoforgespi.language.ModFileScanData;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * NeoForge language loader. Registered via
 * {@code META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader}.
 * <p>
 * FML 4.0.x discovers language loaders on the PLUGIN module layer — the jar carrying this
 * class must declare {@code FMLModType: LIBRARY} in its manifest and must <em>not</em>
 * ship a {@code neoforge.mods.toml} (that would classify it as a MOD instead of a LIBRARY).
 * Mods opt in via their own mods.toml: {@code modLoader = "mclibprovider"}.
 */
public final class McLibLanguageLoader implements IModLanguageLoader {

    public static final String LANGUAGE_ID = "mclibprovider";
    private static final String LANGUAGE_VERSION = "1";
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
    public String version() {
        return LANGUAGE_VERSION;
    }

    @Override
    public ModContainer loadMod(IModInfo info, ModFileScanData scanResults, ModuleLayer gameLayer) {
        String modId = info.getModId();
        Path modFile = info.getOwningFile().getFile().getFilePath();
        // IModFile#findResource spans the mod's combined classes+resources views
        // (used by FML in dev to unify build/classes/... and build/resources/main).
        Path manifestResource = info.getOwningFile().getFile().findResource(MANIFEST_PATH);

        Manifest manifest = readManifest(modFile, manifestResource, modId);
        List<Path> libs = downloadLibs(manifest, modId);

        ModClassLoader loader = COORDINATOR.register(modId, manifest, modFile, libs);
        McLibProvider.registerMod(modId, loader);

        Class<?> entryClass = loadEntryClass(info, loader, modId);
        Object instance = construct(manifest.lang(), entryClass, info, modId);

        return new McLibModContainer(info, instance);
    }

    private static Manifest readManifest(Path modFile, Path manifestResource, String modId) {
        try {
            // Dev runs: `modFile` is a source-set output dir; `manifestResource` points at
            // the unified resources view (covering `build/resources/main/...`). Production:
            // `modFile` is a jar and `manifestResource` is a ZIP-filesystem path pointing
            // at META-INF/mc-jvm-mod.toml inside the same jar. Either way — just read it.
            if (manifestResource != null && Files.exists(manifestResource)) {
                try (InputStream in = Files.newInputStream(manifestResource)) {
                    return ManifestIo.read(in);
                }
            }
            // Fallback: open the jar ourselves. Only valid when modFile is a regular file.
            if (!Files.isDirectory(modFile)) {
                try (var fs = java.nio.file.FileSystems.newFileSystem(modFile, (ClassLoader) null);
                     InputStream in = Files.newInputStream(fs.getPath(MANIFEST_PATH))) {
                    return ManifestIo.read(in);
                }
            }
            throw new IOException("no " + MANIFEST_PATH + " found for mod " + modId
                    + " at " + modFile);
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

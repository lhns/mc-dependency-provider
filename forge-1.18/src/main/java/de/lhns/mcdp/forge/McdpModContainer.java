package de.lhns.mcdp.forge;

import de.lhns.mcdp.api.McdpProvider;
import de.lhns.mcdp.core.EntrypointAdapter;
import de.lhns.mcdp.core.LoaderCoordinator;
import de.lhns.mcdp.core.ModClassLoader;
import de.lhns.mcdp.deps.LibraryCache;
import de.lhns.mcdp.deps.Manifest;
import de.lhns.mcdp.deps.ManifestConsumer;
import de.lhns.mcdp.deps.ManifestIo;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.forgespi.language.IModInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Forge 1.18 {@code ModContainer} subclass for mcdp-loaded mods. Constructed by
 * {@link McdpLanguageProvider.McdpModLanguageLoader#loadMod} once per mod that declares
 * {@code modLoader = "mcdepprovider"} in its {@code mods.toml}.
 *
 * <p>The constructor runs the per-mod registration: read manifest, download libraries,
 * build the per-mod {@link ModClassLoader}, register with {@link McdpProvider}, and
 * instantiate the {@code @Mod}-annotated entry class. Failures throw {@link IllegalStateException}
 * with the mod ID in the message; FML surfaces these in the loading-screen error sheet.
 *
 * <p><b>Status: not yet runtime-verified.</b> Compiles green; first runServer pass against
 * the {@code forge-example-1.18} test mod will surface any API mismatches between this
 * code's expectations and Forge 1.18's actual behavior.
 */
public final class McdpModContainer extends ModContainer {

    private static final String MANIFEST_PATH = "META-INF/mcdepprovider.toml";

    private static final LoaderCoordinator COORDINATOR =
            new LoaderCoordinator(McdpModContainer.class.getClassLoader());
    private static final LibraryCache CACHE = LibraryCache.defaultCache();
    private static final ManifestConsumer CONSUMER = new ManifestConsumer(CACHE);

    private final String entryFqn;
    private final ModClassLoader modLoader;
    private final Object mod;

    McdpModContainer(IModInfo info, String entryFqn) {
        super(info);
        this.entryFqn = entryFqn;
        try {
            // Read manifest from the mod jar.
            Path manifestPath = info.getOwningFile().getFile().findResource(MANIFEST_PATH);
            if (manifestPath == null || !Files.isRegularFile(manifestPath)) {
                throw new IllegalStateException("mcdepprovider: " + info.getModId()
                        + " declares modLoader=mcdepprovider but has no " + MANIFEST_PATH);
            }
            Manifest manifest;
            try (InputStream in = Files.newInputStream(manifestPath)) {
                manifest = ManifestIo.read(in);
            }

            // Resolve libraries (download missing, SHA-verify, return on-disk paths).
            List<Path> libs = CONSUMER.resolveAll(manifest);

            // Build the per-mod ModClassLoader. modPaths defaults to the mod jar; in dev
            // it's the source-set output dirs from manifest.devRoots() (filtered for
            // existence on the consumer's filesystem).
            Path modFile = info.getOwningFile().getFile().getFilePath();
            List<Path> manifestDevRoots = manifest.devRoots().stream()
                    .map(Path::of)
                    .filter(Files::isDirectory)
                    .toList();
            List<Path> modPaths = manifestDevRoots.isEmpty()
                    ? List.of(modFile)
                    : manifestDevRoots;

            this.modLoader = COORDINATOR.register(
                    info.getModId(), manifest, modPaths, libs,
                    McdpModContainer.class.getClassLoader());
            McdpProvider.registerMod(info.getModId(), modLoader);

            // Bridge manifest registration (ADR-0019). Skip silently if absent (mod has no
            // mixins or didn't generate any cross-classloader bridges).
            Path bridgeToml = info.getOwningFile().getFile()
                    .findResource("META-INF/mcdp-bridges.toml");
            if (bridgeToml != null && Files.isRegularFile(bridgeToml)) {
                McdpProvider.registerAutoBridgeManifestToml(modLoader, bridgeToml);
            }

            // Instantiate the entry class via the appropriate language adapter.
            Class<?> entryClass = Class.forName(entryFqn, true, modLoader);
            EntrypointAdapter adapter = EntrypointAdapter.forLang(manifest.lang());
            this.mod = adapter.construct(entryClass);
        } catch (IOException | ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "mcdepprovider: failed to load mod " + info.getModId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean matches(Object mod) {
        return mod == this.mod;
    }

    @Override
    public Object getMod() {
        return mod;
    }

    public String getEntryFqn() {
        return entryFqn;
    }

    public ModClassLoader getModLoader() {
        return modLoader;
    }
}

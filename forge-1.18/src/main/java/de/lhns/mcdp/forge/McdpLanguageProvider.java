package de.lhns.mcdp.forge;

import net.minecraftforge.forgespi.language.ILifecycleEvent;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.language.IModLanguageProvider;
import net.minecraftforge.forgespi.language.ModFileScanData;
import org.objectweb.asm.Type;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Forge {@link IModLanguageProvider} for mcdp on MC 1.18.x (forgespi 4.0.x).
 * Discovered by Forge via
 * {@code META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider}.
 * Mods opt in by setting {@code modLoader = "mcdepprovider"} in their {@code mods.toml}.
 *
 * <p>Lifecycle (per Forge 1.18 FML's invocation order):
 * <ol>
 *   <li>FML scans every mod jar's class file annotations into a {@link ModFileScanData}
 *       and invokes {@link #getFileVisitor()} once per mod jar that declares
 *       {@code modLoader = "mcdepprovider"}. The visitor walks scan data for the
 *       {@code @Mod}-annotated entry FQN and stashes it in {@link #PER_MOD_ENTRY}
 *       keyed by the mod ID.</li>
 *   <li>FML then calls {@link IModLanguageLoader#loadMod(IModInfo, ModFileScanData,
 *       ModuleLayer)} per mod. We construct a {@link McdpModContainer} which extends
 *       {@code net.minecraftforge.fml.ModContainer} and serves as FML's handle on
 *       the mod for lifecycle events.</li>
 * </ol>
 *
 * <p><b>Status:</b> the file visitor + registry are implemented; the {@code loadMod}
 * dispatch + {@link McdpModContainer} subclass are partial — they wire enough to
 * compile and pass FML's null-check, but the per-mod manifest read, library download,
 * {@link de.lhns.mcdp.core.ModClassLoader} build, and entrypoint instantiation are
 * still TODO. See {@link McdpModContainer}'s class Javadoc for the next-step roadmap.
 */
public final class McdpLanguageProvider implements IModLanguageProvider {

    private static final Logger LOG = Logger.getLogger("mcdepprovider");

    public static final String LANGUAGE_ID = "mcdepprovider";
    private static final String MOD_ANNOTATION_DESC = "Lnet/minecraftforge/fml/common/Mod;";

    /**
     * Entry-class FQN keyed by mod ID. Populated by {@link #getFileVisitor()} when FML
     * walks each mod jar; consumed by {@link McdpModLanguageLoader#loadMod} in the
     * downstream construction step.
     */
    static final ConcurrentHashMap<String, String> PER_MOD_ENTRY = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return LANGUAGE_ID;
    }

    @Override
    public Consumer<ModFileScanData> getFileVisitor() {
        return scanData -> {
            // The scanData annotation set lists every annotation found across the mod jar.
            // Find the @Mod-annotated class and remember its FQN for the loadMod step.
            Optional<String> entry = scanData.getAnnotations().stream()
                    .filter(a -> MOD_ANNOTATION_DESC.equals(a.annotationType().getDescriptor()))
                    .findFirst()
                    .map(a -> a.clazz().getClassName());
            entry.ifPresent(fqn -> {
                // The @Mod annotation's value (a String) is the mod ID. Read it from the
                // annotation's parsed map and key our entry registry by it so FML's per-mod
                // dispatch can look up via the IModInfo's getModId().
                String modId = scanData.getAnnotations().stream()
                        .filter(a -> MOD_ANNOTATION_DESC.equals(a.annotationType().getDescriptor()))
                        .filter(a -> fqn.equals(a.clazz().getClassName()))
                        .findFirst()
                        .map(a -> (String) a.annotationData().get("value"))
                        .orElse(fqn);   // fall back to FQN if @Mod has no value
                PER_MOD_ENTRY.put(modId, fqn);
                LOG.info("mcdepprovider: discovered @Mod entry " + fqn
                        + " for modId " + modId);
                // Register our IModLanguageLoader keyed by mod ID. FML reads getTargets() per
                // mod and dispatches loadMod through the matching loader.
                scanData.addLanguageLoader(Map.of(modId, getModLanguageLoader()));
            });
        };
    }

    private static IModLanguageProvider.IModLanguageLoader getModLanguageLoader() {
        return new McdpModLanguageLoader();
    }

    @Override
    public <R extends ILifecycleEvent<R>> void consumeLifecycleEvent(Supplier<R> consumeEvent) {
        // No-op — mcdp doesn't currently hook FML's per-language lifecycle phases.
    }

    /**
     * Forge's inner-interface contract for per-mod construction. FML calls
     * {@link #loadMod(IModInfo, ModFileScanData, ModuleLayer)} once per mod that
     * declares {@code modLoader = "mcdepprovider"}; we return a {@link McdpModContainer}
     * (typed as {@code Object} via the SPI's generic to avoid pulling fmlcore's
     * {@code ModContainer} class into the SPI).
     */
    static final class McdpModLanguageLoader implements IModLanguageProvider.IModLanguageLoader {
        @SuppressWarnings("unchecked")
        @Override
        public <T> T loadMod(IModInfo info, ModFileScanData scanResults, ModuleLayer gameLayer) {
            // Locate the entry FQN we stashed during getFileVisitor's scan. The sentinel-
            // keyed map needs an IModInfo-aware lookup since the visitor doesn't see mod IDs.
            String entryFqn = scanResults.getAnnotations().stream()
                    .filter(a -> MOD_ANNOTATION_DESC.equals(a.annotationType().getDescriptor()))
                    .findFirst()
                    .map(a -> a.clazz().getClassName())
                    .orElseThrow(() -> new IllegalStateException(
                            "mcdepprovider: no @Mod-annotated class in " + info.getModId()));
            // Per-mod registration (manifest read, library download, ModClassLoader build) is
            // not yet implemented. McdpModContainer's class Javadoc has the roadmap.
            return (T) new McdpModContainer(info, entryFqn);
        }
    }
}

package io.github.mclibprovider.neoforge;

import io.github.mclibprovider.api.McLibProvider;
import io.github.mclibprovider.core.EntrypointAdapter;
import io.github.mclibprovider.core.LoaderCoordinator;
import io.github.mclibprovider.core.MixinConfigScanner;
import io.github.mclibprovider.core.ModClassLoader;
import io.github.mclibprovider.core.StdlibPromotion;
import io.github.mclibprovider.deps.LibraryCache;
import io.github.mclibprovider.deps.Manifest;
import io.github.mclibprovider.deps.ManifestConsumer;
import io.github.mclibprovider.deps.ManifestIo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.fml.javafmlmod.AutomaticEventSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageLoader;
import net.neoforged.neoforgespi.language.ModFileScanData;

import java.lang.annotation.ElementType;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final String MANIFEST_PATH = "META-INF/mclibprovider.toml";

    private static final LoaderCoordinator COORDINATOR =
            new LoaderCoordinator(McLibLanguageLoader.class.getClassLoader());

    private static final LibraryCache CACHE = LibraryCache.defaultCache();
    private static final ManifestConsumer CONSUMER = new ManifestConsumer(CACHE);

    private static final StdlibPromotion PROMOTION_POLICY = StdlibPromotion.defaults();
    private static final Object PROMOTION_LOCK = new Object();
    private static volatile Map<String, Manifest.Library> promotionSelection;
    private static volatile URLClassLoader promotedLoader;

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

        ensurePromotionInitialized();
        Map<String, Manifest.Library> selected = promotionSelection;
        Manifest reducedManifest = new Manifest(
                manifest.lang(),
                manifest.sharedPackages(),
                PROMOTION_POLICY.stripPromoted(manifest, selected));
        List<Path> reducedLibs = filterNonPromoted(manifest, libs, selected);
        ClassLoader libParent = promotedLoader != null
                ? promotedLoader
                : McLibLanguageLoader.class.getClassLoader();

        ModClassLoader loader = COORDINATOR.register(
                modId, reducedManifest, List.of(modFile), reducedLibs, libParent);
        McLibProvider.registerMod(modId, loader);
        registerMixinOwnersForNeoForgeMod(info, modId);

        // Build the per-mod IEventBus before construct() so entries that take it as a ctor arg
        // (the FML-default `@Mod` class shape) can subscribe to RegisterEvent / FMLClientSetupEvent
        // / friends from inside the constructor — those are gated to the per-mod bus and fire
        // before any global event the mod could otherwise hook into.
        IEventBus eventBus = newPerModEventBus();
        Dist dist = FMLEnvironment.dist;

        Class<?> entryClass = loadEntryClass(scanResults, loader, modId);
        Object instance = construct(manifest.lang(), entryClass, modId, eventBus, dist);

        McLibModContainer container = new McLibModContainer(info, instance, eventBus);

        // Reuse FML's @EventBusSubscriber scanner directly. It walks scanResults for any
        // @EventBusSubscriber-annotated class belonging to this modId, filters by Dist, and
        // registers each against the right bus (mod-bus or NeoForge.EVENT_BUS). Same machinery
        // FMLModContainer uses for vanilla javafml mods.
        try {
            AutomaticEventSubscriber.inject(container, scanResults, entryClass.getModule());
        } catch (RuntimeException ignored) {
            // Best-effort: a malformed @EventBusSubscriber shouldn't fatally fail mod load.
        }

        return container;
    }

    private static IEventBus newPerModEventBus() {
        return BusBuilder.builder()
                .setExceptionHandler((bus, event, listeners, index, throwable) -> {
                    // Match FMLModContainer's default — failures propagate to the dispatcher.
                    throw new RuntimeException(throwable);
                })
                .markerType(IModBusEvent.class)
                .allowPerPhasePost()
                .build();
    }

    /**
     * NeoForge calls {@link #loadMod} once per mod; StdlibPromotion needs the union of every
     * mod's manifest before it can pick a winner. First-call scans the full {@link LoadingModList},
     * reads each mclibprovider-loaded mod's manifest, runs {@link StdlibPromotion#selectPromotions},
     * builds one shared {@link URLClassLoader} per promoted library, and caches both.
     */
    private static void ensurePromotionInitialized() {
        if (promotionSelection != null) return;
        synchronized (PROMOTION_LOCK) {
            if (promotionSelection != null) return;
            List<Manifest> allManifests = new ArrayList<>();
            try {
                for (IModInfo info : LoadingModList.get().getMods()) {
                    if (!LANGUAGE_ID.equals(info.getLoader().name())) continue;
                    Path modFile = info.getOwningFile().getFile().getFilePath();
                    Path resource = info.getOwningFile().getFile().findResource(MANIFEST_PATH);
                    try {
                        allManifests.add(readManifest(modFile, resource, info.getModId()));
                    } catch (IllegalStateException ignored) {
                        // skip mods we can't parse; loadMod will fail loud when they come through
                    }
                }
            } catch (Throwable t) {
                // If LoadingModList isn't accessible yet or throws, fall back to no-promotion.
                promotionSelection = Collections.emptyMap();
                return;
            }

            Map<String, Manifest.Library> selected = PROMOTION_POLICY.selectPromotions(allManifests);
            if (!selected.isEmpty()) {
                List<Path> jars = new ArrayList<>(selected.size());
                List<String> shas = new ArrayList<>(selected.size());
                for (Manifest.Library lib : selected.values()) {
                    try {
                        jars.add(CONSUMER.resolve(lib));
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "mc-lib-provider: failed to resolve promoted lib " + lib.coords(), e);
                    }
                    shas.add(lib.sha256());
                }
                promotedLoader = COORDINATOR.buildSharedLibraryLoader(jars, shas);
            }
            promotionSelection = selected;
        }
    }

    /**
     * Pre-register mixin class FQNs → owning modId from this mod's {@code neoforge.mods.toml}
     * {@code [[mixins]]} entries (ADR-0008 path 2). Uses FML's own {@link IConfigurable} view so we
     * don't re-parse the TOML. Silent on any failure — the annotation {@code modId} path (path 1)
     * stays primary.
     */
    private static void registerMixinOwnersForNeoForgeMod(IModInfo info, String modId) {
        try {
            IConfigurable fileConfig = info.getOwningFile().getConfig();
            if (fileConfig == null) return;
            List<String> configPaths = new ArrayList<>();
            for (IConfigurable entry : fileConfig.getConfigList("mixins")) {
                entry.<String>getConfigElement("config").ifPresent(configPaths::add);
            }
            if (configPaths.isEmpty()) return;

            List<Path> resolved = new ArrayList<>(configPaths.size());
            List<String> relative = new ArrayList<>(configPaths.size());
            for (String cfg : configPaths) {
                Path p = info.getOwningFile().getFile().findResource(cfg);
                if (p != null) {
                    // Normalize to a root+relative view MixinConfigScanner expects.
                    resolved.add(p.getParent() != null ? p.getParent() : p);
                    relative.add(p.getFileName().toString());
                }
            }
            for (int k = 0; k < resolved.size(); k++) {
                MixinConfigScanner.registerMixinOwnersFromConfigs(
                        modId, List.of(resolved.get(k)), List.of(relative.get(k)));
            }
        } catch (RuntimeException ignored) {
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

    private static Manifest readManifest(Path modFile, Path manifestResource, String modId) {
        try {
            // Dev runs: `modFile` is a source-set output dir; `manifestResource` points at
            // the unified resources view (covering `build/resources/main/...`). Production:
            // `modFile` is a jar and `manifestResource` is a ZIP-filesystem path pointing
            // at META-INF/mclibprovider.toml inside the same jar. Either way — just read it.
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

    /**
     * Discover the entry class via {@code @Mod("modid")} annotation in the mod's scan results —
     * the same mechanism FML's vanilla {@code javafmlmod} loader uses. Mods declare exactly as
     * they would on a regular NeoForge mod; the only difference vs vanilla is
     * {@code modLoader = "mclibprovider"} in {@code neoforge.mods.toml}.
     */
    private static Class<?> loadEntryClass(ModFileScanData scanResults, ModClassLoader loader, String modId) {
        String fqn = scanResults.getAnnotatedBy(Mod.class, ElementType.TYPE)
                .filter(a -> modId.equals(a.annotationData().get("value")))
                .map(a -> a.clazz().getClassName())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "mc-lib-provider: no @Mod(\"" + modId + "\")-annotated class found for "
                                + modId + ". Add @Mod(\"" + modId + "\") to your entry class."));
        try {
            return Class.forName(fqn, true, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "mc-lib-provider: @Mod-annotated class not loadable for " + modId + ": " + fqn, e);
        }
    }

    private static Object construct(String lang, Class<?> entryClass, String modId, Object... contextBag) {
        try {
            return EntrypointAdapter.forLang(lang).construct(entryClass, contextBag);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "mc-lib-provider: failed to instantiate entrypoint for " + modId, e);
        }
    }
}

package io.github.mclibprovider.fabric;

import io.github.mclibprovider.api.McLibProvider;
import io.github.mclibprovider.core.EntrypointAdapter;
import io.github.mclibprovider.core.ModClassLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

/**
 * Fabric's {@link LanguageAdapter} that dispatches into {@link EntrypointAdapter}.
 * <p>
 * Fabric calls {@code create(container, value, type)} for each entrypoint declared with
 * {@code "adapter": "mclibprovider"}. By that time {@link McLibPreLaunch} has already populated
 * the {@link ModClassLoader} for this mod, so we:
 * <ol>
 *   <li>look up the mod's loader via {@link McLibProvider#loaderFor(String)};</li>
 *   <li>load the entrypoint class through it — isolating the mod's Maven deps;</li>
 *   <li>construct via the language-specific adapter.</li>
 * </ol>
 */
public final class McLibLanguageAdapter implements LanguageAdapter {

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        String modId = mod.getMetadata().getId();
        ModClassLoader loader = McLibProvider.loaderFor(modId);
        if (loader == null) {
            throw new LanguageAdapterException(
                    "mc-lib-provider: ModClassLoader for '" + modId + "' not initialized."
                            + " Ensure McLibPreLaunch ran and the mod declares its manifest.");
        }

        String lang = McLibPreLaunch.langFor(modId);

        Class<?> entryClass;
        try {
            entryClass = Class.forName(value, true, loader);
        } catch (ClassNotFoundException e) {
            throw new LanguageAdapterException("mc-lib-provider: entrypoint class not found: " + value, e);
        }

        // Fabric entrypoints are usually no-arg objects or classes with a no-arg ctor. Our
        // EntrypointAdapter falls back to the 0-arg constructor after any singleton check.
        Object instance;
        try {
            instance = EntrypointAdapter.forLang(lang).construct(entryClass);
        } catch (ReflectiveOperationException e) {
            throw new LanguageAdapterException("mc-lib-provider: failed to instantiate " + value, e);
        }

        if (!type.isInstance(instance)) {
            throw new LanguageAdapterException("mc-lib-provider: " + value
                    + " (lang=" + lang + ") does not implement " + type.getName());
        }
        return type.cast(instance);
    }
}

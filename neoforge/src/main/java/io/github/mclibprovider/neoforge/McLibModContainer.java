package io.github.mclibprovider.neoforge;

import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * Minimal {@link ModContainer} subclass that wraps an already-constructed mod instance.
 * <p>
 * NeoForge's call-sites cast the generic return of {@code IModLanguageProvider.IModLanguageLoader#loadMod}
 * to {@code ModContainer}; returning the raw mod instance raised {@code ClassCastException} downstream.
 * This wrapper supplies the minimum surface NeoForge needs:
 * <ul>
 *   <li>an {@link IEventBus} for the mod event dispatch, and</li>
 *   <li>a no-op {@link #constructMod()} — our mod instance is already built by the
 *       {@link io.github.mclibprovider.core.EntrypointAdapter} pipeline, so we have nothing to do
 *       when NeoForge calls back.</li>
 * </ul>
 * Reflection-driven NeoForge subsystems (registries, networking) interact via the mod's
 * {@link Class}, so they do not need the instance on this container directly; {@link #getModInstance()}
 * is retained for callers that want it.
 */
public final class McLibModContainer extends ModContainer {

    private final Object modInstance;
    private final IEventBus eventBus;

    public McLibModContainer(IModInfo info, Object modInstance) {
        super(info);
        this.modInstance = modInstance;
        this.eventBus = BusBuilder.builder()
                .setExceptionHandler((bus, event, listeners, index, throwable) -> {
                    // Match FMLModContainer's default: rethrow so failures propagate to the dispatcher.
                    throw new RuntimeException(throwable);
                })
                .markerType(net.neoforged.fml.event.IModBusEvent.class)
                .allowPerPhasePost()
                .build();
    }

    public Object getModInstance() {
        return modInstance;
    }

    @Override
    public IEventBus getEventBus() {
        return eventBus;
    }

    @Override
    protected void constructMod() {
        // Already constructed upstream — nothing to do.
    }
}

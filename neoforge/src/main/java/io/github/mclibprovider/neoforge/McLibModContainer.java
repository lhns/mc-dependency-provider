package io.github.mclibprovider.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * Minimal {@link ModContainer} subclass that wraps an already-constructed mod instance and exposes
 * the per-mod {@link IEventBus} that {@link McLibLanguageLoader} built before constructing the
 * mod. The bus is shared between the mod's constructor (so the mod can subscribe to mod-bus
 * events like {@code RegisterEvent} from inside its ctor) and this container (so NeoForge's
 * dispatcher routes events through the same bus).
 */
public final class McLibModContainer extends ModContainer {

    private final Object modInstance;
    private final IEventBus eventBus;

    public McLibModContainer(IModInfo info, Object modInstance, IEventBus eventBus) {
        super(info);
        this.modInstance = modInstance;
        this.eventBus = eventBus;
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

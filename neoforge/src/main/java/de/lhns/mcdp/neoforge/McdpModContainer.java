package de.lhns.mcdp.neoforge;

import de.lhns.mcdp.core.EntrypointAdapter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.fml.javafmlmod.AutomaticEventSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;

/**
 * {@link ModContainer} subclass that mirrors {@code FMLModContainer}'s lifecycle:
 * the container builds itself first (with bus + entry-class recipe but no instance);
 * FML then drives {@link #constructMod()} at the {@code CONSTRUCT} lifecycle step,
 * at which point we instantiate the entry class with {@code this} (the container) in
 * the context bag — enabling {@code (IEventBus, ModContainer, Dist)} ctor permutations
 * on mod entry classes.
 * <p>
 * The per-mod {@link IEventBus} is built here, identical recipe to vanilla
 * {@code FMLModContainer}: {@code BusBuilder} with {@link IModBusEvent} marker and
 * per-phase posting. Every {@link ModContainer} subclass owns its own bus —
 * {@link ModContainer#getEventBus()} is abstract on the parent, and FML provides no
 * shared factory.
 */
public final class McdpModContainer extends ModContainer {

    private final IEventBus eventBus;
    private final Class<?> entryClass;
    private final String lang;
    private final ModFileScanData scanResults;
    private volatile Object modInstance;

    public McdpModContainer(IModInfo info, Class<?> entryClass, String lang,
                            ModFileScanData scanResults) {
        super(info);
        this.eventBus = BusBuilder.builder()
                .setExceptionHandler((bus, event, listeners, index, throwable) -> {
                    // Match FMLModContainer's default — failures propagate to the dispatcher.
                    throw new RuntimeException(throwable);
                })
                .markerType(IModBusEvent.class)
                .allowPerPhasePost()
                .build();
        this.entryClass = entryClass;
        this.lang = lang;
        this.scanResults = scanResults;
    }

    @Override
    public IEventBus getEventBus() {
        return eventBus;
    }

    /**
     * Mod instance after {@link #constructMod()} has run; {@code null} beforehand. Vanilla
     * {@code FMLModContainer} discards the instance once the ctor's side effects have run;
     * we keep it because {@link McdpModContainerTest} asserts on it. Production paths do
     * not reference this.
     */
    public Object getModInstance() {
        return modInstance;
    }

    @Override
    protected void constructMod() {
        // Production: FML sets FMLEnvironment.dist before any loadMod call. Tests: it can be
        // null because they don't go through FML init. Drop it from the bag in that case so
        // bag matching doesn't fail entry-class ctors that don't take Dist.
        Dist dist = FMLEnvironment.dist;
        Object[] bag = (dist != null)
                ? new Object[] { eventBus, this, dist }
                : new Object[] { eventBus, this };
        try {
            this.modInstance = EntrypointAdapter.forLang(lang).construct(entryClass, bag);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "mcdepprovider: failed to instantiate entrypoint for " + getModId(), e);
        }
        try {
            // Reuse FML's @EventBusSubscriber scanner directly. Walks scanResults for any
            // @EventBusSubscriber-annotated class belonging to this modId, filters by Dist,
            // and registers each against the right bus. Same machinery FMLModContainer uses.
            AutomaticEventSubscriber.inject(this, scanResults, entryClass.getModule());
        } catch (RuntimeException ignored) {
            // Best-effort: a malformed @EventBusSubscriber shouldn't fatally fail mod load.
        }
    }
}

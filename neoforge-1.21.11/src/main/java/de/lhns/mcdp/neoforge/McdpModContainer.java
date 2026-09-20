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
 * {@link ModContainer} subclass for the MC 1.21.11 band (FML loader 10.0.x). Mirrors
 * {@code FMLModContainer}'s lifecycle: the container builds itself first (with bus + entry-class
 * recipe but no instance); FML then drives {@link #constructMod()} at the {@code CONSTRUCT}
 * lifecycle step, at which point we instantiate the entry class with {@code this} (the container)
 * in the context bag — enabling {@code (IEventBus, ModContainer, Dist)} ctor permutations on mod
 * entry classes.
 *
 * <p>Differences from the canonical 1.21.1 container (ADR-0030):
 * <ul>
 *   <li>{@code FMLEnvironment.dist} (public static field, FML ≤ 9.0.x) became
 *       {@code FMLEnvironment.getDist()} in FML 10.0.x. Reading the field against a 10.0
 *       runtime is a {@code NoSuchFieldError} at link time, which is why this band cannot
 *       share {@code neoforge/}'s source tree.</li>
 *   <li>No {@code contextExtension} assignment. FML dropped that field back in 4.0.x
 *       (ADR-0023, "Operational findings"), and 10.0.x still has no such field — verified with
 *       {@code javap} against {@code fancymodloader:loader:10.0.36}.</li>
 * </ul>
 *
 * <p>{@code BusBuilder}/{@code IEventBus} come from {@code net.neoforged:bus}, which NeoForge
 * 21.1.251 and 21.11.45 both pin at 8.0.5 — identical surface, no port needed.
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
     * we keep it for parity with the 1.21.1 band's tests. Production paths do not reference it.
     */
    public Object getModInstance() {
        return modInstance;
    }

    @Override
    protected void constructMod() {
        // Production: FML sets the dist before any loadMod call. Tests: it can be null because
        // they don't go through FML init. Drop it from the bag in that case so bag matching
        // doesn't fail entry-class ctors that don't take Dist.
        Dist dist = currentDist();
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
            // `inject` is unchanged between FML 4.0.42 and 10.0.36.
            AutomaticEventSubscriber.inject(this, scanResults, entryClass.getModule());
        } catch (RuntimeException ignored) {
            // Best-effort: a malformed @EventBusSubscriber shouldn't fatally fail mod load.
        }
    }

    /**
     * FML 10.0.x exposes the dist as {@code FMLEnvironment.getDist()}; the pre-10 public static
     * {@code dist} field is gone. Outside FML init (unit tests) the accessor can throw rather
     * than return null, so it is guarded here exactly where the old field read was null-checked.
     */
    private static Dist currentDist() {
        try {
            return FMLEnvironment.getDist();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}

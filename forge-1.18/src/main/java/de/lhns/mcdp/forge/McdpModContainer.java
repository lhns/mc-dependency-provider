package de.lhns.mcdp.forge;

import de.lhns.mcdp.core.EntrypointAdapter;
import de.lhns.mcdp.core.ModClassLoader;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.event.IModBusEvent;
import net.minecraftforge.forgespi.Environment;
import net.minecraftforge.forgespi.language.IModInfo;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Forge {@code ModContainer} subclass for mcdp-loaded mods, shared by the MC 1.18.x
 * (forgespi 4.0.x / fmlcore 1.18.2) and MC 1.20.x (forgespi 7.x / fmlcore 1.20.1) bands.
 * Constructed by {@link McdpLanguageProvider.McdpModLanguageLoader#loadMod} once per mod
 * that declares {@code modLoader = "mcdepprovider"} in its {@code mods.toml}.
 *
 * <p>Two phases, mirroring vanilla {@code FMLModContainer} (see ADR-0027):
 * <ol>
 *   <li><b>Constructor (FML's {@code loadMod} dispatch).</b> Delegates per-mod registration —
 *       manifest read, library download, stdlib promotion, {@link ModClassLoader} construction,
 *       provider and bridge-manifest registration — to
 *       {@link McdpLanguageProvider#ensureRegistered}, which may already have run from the lazy
 *       populator (ADR-0028). Then resolves (but does not initialize) the entry class and builds
 *       the per-mod mod {@link IEventBus}. No mod code runs yet.</li>
 *   <li><b>{@code CONSTRUCT} lifecycle stage.</b> The {@link #activityMap} entry registered by
 *       the constructor runs {@link #constructMod()}, which instantiates the entry class with
 *       the ADR-0017 context bag {@code (IEventBus, ModContainer, Dist)}.</li>
 * </ol>
 * From then on {@link #acceptEvent} forwards every {@code IModBusEvent} FML posts for this mod
 * ({@code FMLCommonSetupEvent}, {@code FMLClientSetupEvent}, {@code InterModEnqueueEvent}, …)
 * onto that bus. Failures throw {@link IllegalStateException} with the mod ID in the message;
 * FML surfaces these in the loading-screen error sheet.
 *
 * <p><b>Status:</b> the 1.20 band is runtime-verified — {@code forge-example-1.20} boots in CI.
 * The 1.18 band has no CI cell yet and is compile-verified only; the source is shared, but the
 * forgespi 4.0.x / eventbus 5.0.7 pairing has not been exercised at runtime.
 *
 * <p><b>Known limitation.</b> {@code FMLJavaModLoadingContext.get()} does not work through mcdp
 * on Forge: its constructor is package-private in {@code javafmllanguage}, so
 * {@link #contextExtension} carries a non-null sentinel ({@code this}) instead. Mods that need
 * the mod bus take it as a constructor parameter. For the same reason Forge's
 * {@code AutomaticEventSubscriber} is out of reach, so {@code @EventBusSubscriber} classes are
 * not auto-registered on this adapter (the NeoForge adapter does support them).
 */
public final class McdpModContainer extends ModContainer {

    private static final Logger LOG = Logger.getLogger("mcdepprovider");

    private final String entryFqn;
    private final Class<?> entryClass;
    private final String lang;
    private final IEventBus eventBus;
    private volatile Object mod;

    McdpModContainer(IModInfo info, String entryFqn) {
        super(info);
        // ModLoadingContext.setActiveContainer .get()s contextExtension during every lifecycle
        // transition, so it must be non-null; see the "Known limitation" note above for why it
        // cannot be the real FMLJavaModLoadingContext.
        this.contextExtension = () -> this;
        this.entryFqn = entryFqn;

        // ensureRegistered is idempotent: the lazy populator may already have run it when a
        // mixin <clinit> beat FML's loadMod sweep (ADR-0028).
        McdpLanguageProvider.Registered reg = McdpLanguageProvider.ensureRegistered(info);
        ModClassLoader modLoader = reg.loader();
        this.lang = reg.manifest().lang();
        try {
            // Resolve the entry class without initializing it — static init belongs to the
            // CONSTRUCT stage, alongside the entry constructor.
            this.entryClass = Class.forName(entryFqn, false, modLoader);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "mcdepprovider: failed to load mod " + info.getModId() + ": " + e, e);
        }

        // Per-mod mod-event bus. Same recipe as vanilla FMLModContainer, restricted to the
        // BusBuilder methods that exist identically on eventbus 5.0.7 (1.18) and 6.x (1.20):
        // builder(), setExceptionHandler(), markerType(), build().
        this.eventBus = BusBuilder.builder()
                .setExceptionHandler((bus, event, listeners, index, throwable) -> {
                    // Vanilla Forge only logs here. mcdp propagates as well, so a listener
                    // failure in an mcdp-loaded mod lands in FML's error sheet instead of
                    // scrolling past in the log (ADR-0027).
                    LOG.log(Level.SEVERE, "mcdepprovider: " + getModId()
                            + " failed handling " + event.getClass().getName(), throwable);
                    throw new RuntimeException(throwable);
                })
                .markerType(IModBusEvent.class)
                .build();

        // FML runs activityMap.getOrDefault(stage, noop).run() before posting each stage's
        // mod-bus event (ModContainer.buildTransitionHandler). CONSTRUCT is where vanilla
        // FMLModContainer instantiates the mod, so that is where we do it too.
        this.activityMap.put(ModLoadingStage.CONSTRUCT, this::constructMod);
    }

    /**
     * The mod's own event bus. Passed into the entry constructor via the ADR-0017 bag, and
     * the target of every mod-bus event FML routes to this container.
     */
    public IEventBus getEventBus() {
        return eventBus;
    }

    /**
     * Instantiate the entry class. Driven by FML at the {@code CONSTRUCT} lifecycle stage via
     * {@link #activityMap}; not called directly.
     */
    void constructMod() {
        // Production: modlauncher builds forgespi's Environment long before any loadMod call.
        // Outside FML (unit tests) it is null — drop Dist from the bag in that case so bag
        // matching doesn't fail entry-class ctors that don't take one.
        Environment env = Environment.get();
        Dist dist = (env != null) ? env.getDist() : null;
        Object[] bag = (dist != null)
                ? new Object[] { eventBus, this, dist }
                : new Object[] { eventBus, this };
        try {
            this.mod = EntrypointAdapter.forLang(lang).construct(entryClass, bag);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("mcdepprovider: failed to instantiate entrypoint "
                    + entryFqn + " for mod " + getModId() + ": " + e, e);
        }
    }

    /**
     * Forwards the mod-bus events FML posts per lifecycle stage ({@code FMLConstructModEvent},
     * {@code FMLCommonSetupEvent}, {@code FMLClientSetupEvent}, {@code InterModEnqueueEvent},
     * {@code InterModProcessEvent}, {@code FMLLoadCompleteEvent}) onto this mod's bus. Vanilla
     * {@code ModContainer.acceptEvent} is a no-op, which is why mcdp mods received no lifecycle
     * events before this override existed.
     */
    @Override
    protected <T extends Event & IModBusEvent> void acceptEvent(T e) {
        try {
            eventBus.post(e);
        } catch (Throwable t) {
            throw new IllegalStateException("mcdepprovider: " + getModId()
                    + " failed handling " + e.getClass().getName(), t);
        }
    }

    @Override
    public boolean matches(Object mod) {
        return mod != null && mod == this.mod;
    }

    /** The mod instance, or {@code null} before the {@code CONSTRUCT} stage has run. */
    @Override
    public Object getMod() {
        return mod;
    }
}

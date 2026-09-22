package de.lhns.mcdp.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.event.IModBusEvent;
import net.minecraftforge.forgespi.Environment;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.language.ModFileScanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static de.lhns.mcdp.forge.ForgeStubs.langOnly;
import static de.lhns.mcdp.forge.ForgeStubs.modInDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Forge container's two phases (ADR-0027): the constructor registers the mod and builds its
 * bus without running mod code; {@code constructMod()} — FML's {@code CONSTRUCT} activity —
 * instantiates the entry class from the context bag {@code (IEventBus, ModContainer, Dist)}.
 * The first five tests are the NeoForge container suite ported to Forge.
 *
 * <p>Unlike NeoForge's, this constructor runs the real {@code ensureRegistered}: each mod gets a
 * directory with a {@code META-INF/mcdepprovider.toml} and zero libraries, so registration does
 * no I/O beyond reading it. The entry classes are nested here; the mod directory holds no
 * classes, so the per-mod loader falls through to the test classpath and hands back these very
 * {@code Class} objects.
 *
 * <p>Runs on all four Forge bands. That matters here more than anywhere: {@code BusBuilder} is a
 * class on eventbus 5.0.7 and an interface on 6.x, so the container's bytecode differs per band,
 * and fmlcore 1.18+'s {@code ModContainer} constructor reads the {@code displayTest} config
 * (answered by {@link ForgeStubs#emptyConfig()}) where 1.17's does not. Every test uses its own
 * modId: registration state is process-global.
 */
class McdpModContainerTest {

    @TempDir
    Path tmp;

    // --- Ported from neoforge/…/McdpModContainerTest ------------------------------------------

    @Test
    void constructModRunsCtorWithBusAndContainer() throws Exception {
        McdpModContainer container = container("forge_full_bag_mod", BusAndContainerCapture.class);

        assertInstanceOf(ModContainer.class, container);
        assertNotNull(container.getEventBus(), "bus must be built before constructMod runs");
        assertNull(container.getMod(), "instance must be null until constructMod fires");

        container.constructMod();

        BusAndContainerCapture instance = (BusAndContainerCapture) container.getMod();
        assertNotNull(instance, "constructMod must populate the mod instance");
        assertSame(container.getEventBus(), instance.bus,
                "the bus passed into the ctor must be the container's own bus");
        assertSame(container, instance.container,
                "the container passed into the ctor must be `this`");
        assertTrue(container.matches(instance));
    }

    @Test
    void constructModRunsCtorWithSubsetBag() throws Exception {
        McdpModContainer container = container("forge_subset_bag_mod", ContainerOnlyCapture.class);

        container.constructMod();

        ContainerOnlyCapture instance = (ContainerOnlyCapture) container.getMod();
        assertNotNull(instance);
        assertSame(container, instance.container,
                "bag matcher picks ModContainer out of the trio when that's the only declared param");
    }

    @Test
    void constructModRunsNoArgCtor() throws Exception {
        McdpModContainer container = container("forge_no_arg_mod", NoArgCapture.class);

        container.constructMod();

        NoArgCapture instance = (NoArgCapture) container.getMod();
        assertNotNull(instance, "no-arg ctor must remain a valid entry shape");
        assertTrue(instance.constructed, "ctor body must have executed");
    }

    @Test
    void constructModWrapsReflectiveFailure() throws Exception {
        McdpModContainer container = container("forge_throwing_mod", ThrowingCapture.class);

        IllegalStateException ex = assertThrows(IllegalStateException.class, container::constructMod);
        assertTrue(ex.getMessage().startsWith("mcdepprovider: failed to instantiate entrypoint"),
                "ctor failures must be wrapped with a clear mcdepprovider-prefixed message");
        assertNull(container.getMod(), "failed construction must not populate the mod instance");
    }

    @Test
    void wrapperExposesContainerIdentity() throws Exception {
        McdpModContainer container = container("forge_identity_mod", NoArgCapture.class);

        assertEquals("forge_identity_mod", container.getModId());
        assertNotNull(container.getEventBus());
        assertInstanceOf(IEventBus.class, container.getEventBus());
    }

    // --- Item 6 --------------------------------------------------------------------------------

    /**
     * An unknown {@code lang} used to escape as {@code EntrypointAdapter.forLang}'s bare
     * {@code IllegalArgumentException("unsupported lang: …")} — the only construct failure that
     * did not name the mod.
     */
    @Test
    void unknownLangFailsNamingTheMod() throws Exception {
        IModInfo info = modInDirectory("forge_cobol_mod", tmp.resolve("cobol"), langOnly("cobol"));
        McdpModContainer container = new McdpModContainer(info, NoArgCapture.class.getName());

        IllegalStateException ex = assertThrows(IllegalStateException.class, container::constructMod);
        assertTrue(ex.getMessage().startsWith("mcdepprovider: "), ex.getMessage());
        assertTrue(ex.getMessage().contains("forge_cobol_mod"), ex.getMessage());
        assertTrue(ex.getMessage().contains("cobol\""), ex.getMessage());
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertNull(container.getMod());
    }

    // --- New on Forge: Dist, event forwarding, multi-mod jars ---------------------------------

    /**
     * Forge's {@code Dist} reaches the bag through forgespi's {@code Environment}, which
     * modlauncher builds in production and which is null in a unit test. The NeoForge suite
     * could not cover this ({@code FMLEnvironment.dist} is a static final there); on Forge the
     * singleton is a plain private static, so it is installed for the duration of the test and
     * cleared afterwards. The instance is allocated without its constructor, which needs a
     * modlauncher {@code IEnvironment} whose key registry differs between modlauncher 9 and 10;
     * {@code constructMod} reads nothing but {@code getDist()} from it.
     */
    @Test
    void constructModPassesTheEnvironmentsDist() throws Exception {
        assertNull(Environment.get(), "premise: no Environment outside FML");
        McdpModContainer container = container("forge_dist_mod", DistCapture.class);

        installEnvironment(Dist.DEDICATED_SERVER);
        try {
            container.constructMod();
        } finally {
            installEnvironment(null);
        }

        DistCapture instance = (DistCapture) container.getMod();
        assertNotNull(instance, "a Dist-only entry ctor must be satisfiable once FML is up");
        assertEquals(Dist.DEDICATED_SERVER, instance.dist);
    }

    /**
     * {@code ModContainer.acceptEvent} is a no-op in fmlcore; this override is the only thing
     * that delivers {@code FMLCommonSetupEvent} and friends to an mcdp mod's bus.
     */
    @Test
    void acceptEventPostsOntoTheModBus() throws Exception {
        McdpModContainer container = container("forge_accept_event_mod", NoArgCapture.class);
        List<TestModBusEvent> seen = new ArrayList<>();
        container.getEventBus().addListener(EventPriority.NORMAL, false, TestModBusEvent.class,
                seen::add);

        TestModBusEvent event = new TestModBusEvent();
        container.acceptEvent(event);

        assertEquals(1, seen.size(), "the listener on the mod bus must see the event once");
        assertSame(event, seen.get(0));
    }

    /** A listener failure has to reach FML (and its error sheet) naming the mod. */
    @Test
    void acceptEventSurfacesListenerFailuresNamingTheMod() throws Exception {
        McdpModContainer container = container("forge_failing_listener_mod", NoArgCapture.class);
        container.getEventBus().addListener(EventPriority.NORMAL, false, TestModBusEvent.class,
                e -> { throw new IllegalStateException("listener boom"); });

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> container.acceptEvent(new TestModBusEvent()));
        assertTrue(ex.getMessage().startsWith("mcdepprovider: forge_failing_listener_mod"),
                ex.getMessage());
        assertTrue(causeChainMentions(ex, "listener boom"), "the listener's exception is lost");
    }

    /**
     * Item 8, end to end through {@code loadMod}: a jar declaring two mods must give each its
     * own entry class. The first {@code @Mod} in the scan belongs to the other mod, so taking
     * the first one constructs the wrong class.
     */
    @Test
    void loadModPicksTheEntryClassOfItsOwnModId() throws Exception {
        IModInfo info = modInDirectory("forge_second_of_two", tmp.resolve("second"), langOnly("java"));
        ModFileScanData scan = new ModFileScanData();
        scan.getAnnotations().add(McdpLanguageProviderTest.modAnnotation(
                NoArgCapture.class.getName(), "forge_first_of_two"));
        scan.getAnnotations().add(McdpLanguageProviderTest.modAnnotation(
                ContainerOnlyCapture.class.getName(), "forge_second_of_two"));

        McdpModContainer container =
                new McdpLanguageProvider.McdpModLanguageLoader().loadMod(info, scan, null);
        container.constructMod();

        assertInstanceOf(ContainerOnlyCapture.class, container.getMod());
    }

    // --- entry-class fixtures -----------------------------------------------------------------

    public static final class BusAndContainerCapture {
        final IEventBus bus;
        final ModContainer container;

        public BusAndContainerCapture(IEventBus bus, ModContainer container) {
            this.bus = bus;
            this.container = container;
        }
    }

    /** Bag matcher must pick ModContainer out even when bus + dist are also present. */
    public static final class ContainerOnlyCapture {
        final ModContainer container;

        public ContainerOnlyCapture(ModContainer container) {
            this.container = container;
        }
    }

    /** Backward-compat: a no-arg ctor still works. */
    public static final class NoArgCapture {
        boolean constructed;

        public NoArgCapture() {
            this.constructed = true;
        }
    }

    /** Forces constructMod down its failure path. */
    public static final class ThrowingCapture {
        public ThrowingCapture() {
            throw new RuntimeException("boom");
        }
    }

    /** Satisfiable only when the bag carries a Dist. */
    public static final class DistCapture {
        final Dist dist;

        public DistCapture(Dist dist) {
            this.dist = dist;
        }
    }

    /**
     * A mod-bus event. Public with a public no-arg constructor: outside modlauncher no
     * transformer adds the listener-list plumbing, and eventbus falls back to instantiating the
     * class reflectively to compute it.
     */
    public static class TestModBusEvent extends Event implements IModBusEvent {
        public TestModBusEvent() {}
    }

    // --- helpers ------------------------------------------------------------------------------

    private McdpModContainer container(String modId, Class<?> entry) throws Exception {
        IModInfo info = modInDirectory(modId, tmp.resolve(modId), langOnly("java"));
        return new McdpModContainer(info, entry.getName());
    }

    /**
     * Sets forgespi's {@code Environment.INSTANCE} — a private, non-final static on forgespi
     * 4.0.11, 6.0.0 and 7.1.6 alike — to an instance whose only initialized field is
     * {@code dist}; {@code null} clears it.
     */
    private static void installEnvironment(Dist dist) throws Exception {
        Object env = null;
        if (dist != null) {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            env = unsafeClass.getMethod("allocateInstance", Class.class)
                    .invoke(unsafe, Environment.class);
            Field distField = Environment.class.getDeclaredField("dist");
            distField.setAccessible(true);
            distField.set(env, dist);
        }
        Field instance = Environment.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, env);
    }

    private static boolean causeChainMentions(Throwable t, String text) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(text)) return true;
        }
        return false;
    }
}

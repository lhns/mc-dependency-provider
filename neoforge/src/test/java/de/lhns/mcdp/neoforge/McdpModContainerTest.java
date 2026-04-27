package de.lhns.mcdp.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the lifecycle pattern from ADR-0017: the container builds itself first (with bus +
 * entry-class recipe), and {@link ModContainer#constructMod()} invokes the entry ctor with the
 * container in the context bag. The bag-matching ({@link de.lhns.mcdp.core.JavaEntrypointAdapter})
 * accepts every signature drawn from {@code {IEventBus, ModContainer, Dist}} (and subsets).
 * <p>
 * {@link IModInfo} is stubbed via dynamic proxy because the real implementations live in the FML
 * runtime not on the unit-test classpath. {@link ModFileScanData} is constructed empty —
 * {@code AutomaticEventSubscriber.inject} accepts it and finds zero subscribers, which is what we
 * want here (it would otherwise need scanned annotation data).
 */
class McdpModContainerTest {

    @Test
    void constructModRunsCtorWithBusAndContainer() {
        IModInfo info = stubInfo("full_bag_mod");
        McdpModContainer container = new McdpModContainer(
                info, BusAndContainerCapture.class, "java", emptyScan());

        assertInstanceOf(ModContainer.class, container);
        assertNotNull(container.getEventBus(), "bus must be built before constructMod runs");
        assertNull(container.getModInstance(), "instance must be null until constructMod fires");

        invokeConstructMod(container);

        BusAndContainerCapture instance = (BusAndContainerCapture) container.getModInstance();
        assertNotNull(instance, "constructMod must populate modInstance");
        assertSame(container.getEventBus(), instance.bus,
                "the bus passed into the ctor must be the container's own bus");
        assertSame(container, instance.container,
                "the container passed into the ctor must be `this`");
    }

    @Test
    void constructModRunsCtorWithSubsetBag() {
        IModInfo info = stubInfo("subset_bag_mod");
        McdpModContainer container = new McdpModContainer(
                info, ContainerOnlyCapture.class, "java", emptyScan());

        invokeConstructMod(container);

        ContainerOnlyCapture instance = (ContainerOnlyCapture) container.getModInstance();
        assertNotNull(instance);
        assertSame(container, instance.container,
                "bag matcher picks ModContainer out of the trio when that's the only declared param");
    }

    @Test
    void constructModRunsNoArgCtor() {
        IModInfo info = stubInfo("no_arg_mod");
        McdpModContainer container = new McdpModContainer(
                info, NoArgCapture.class, "java", emptyScan());

        invokeConstructMod(container);

        NoArgCapture instance = (NoArgCapture) container.getModInstance();
        assertNotNull(instance, "no-arg ctor must remain a valid entry shape");
        assertTrue(instance.constructed, "ctor body must have executed");
    }

    @Test
    void constructModWrapsReflectiveFailure() {
        IModInfo info = stubInfo("throwing_mod");
        McdpModContainer container = new McdpModContainer(
                info, ThrowingCapture.class, "java", emptyScan());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> invokeConstructMod(container));
        assertTrue(ex.getMessage().startsWith("mcdepprovider: failed to instantiate entrypoint"),
                "ctor failures must be wrapped with a clear mcdepprovider-prefixed message");
        assertNull(container.getModInstance(), "failed construction must not populate modInstance");
    }

    @Test
    void wrapperExposesContainerIdentity() {
        IModInfo info = stubInfo("identity_mod");
        McdpModContainer container = new McdpModContainer(
                info, NoArgCapture.class, "java", emptyScan());

        assertEquals("identity_mod", container.getModId());
        assertNotNull(container.getEventBus());
        assertInstanceOf(IEventBus.class, container.getEventBus());
    }

    // --- entry-class fixtures ---------------------------------------------------------------

    /**
     * Captures the two args we can verify in unit tests. The Dist arg is exercised live via
     * the kotlin-example smoke (FMLEnvironment.dist is null in unit tests because nothing
     * went through FML init, and the field is static-final so reflection can't backfill it).
     */
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

    // --- helpers -----------------------------------------------------------------------------

    private static void invokeConstructMod(McdpModContainer container) {
        try {
            Method m = ModContainer.class.getDeclaredMethod("constructMod");
            m.setAccessible(true);
            m.invoke(container);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            throw new AssertionError(ite.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("constructMod must be invokable", e);
        }
    }

    private static ModFileScanData emptyScan() {
        // Default ctor leaves annotations + classes as empty sets — exactly what we want;
        // AutomaticEventSubscriber.inject iterates them and finds no @EventBusSubscriber to wire.
        return new ModFileScanData();
    }

    private static IModInfo stubInfo(String modId) {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "getModId" -> modId;
            case "getNamespace" -> modId;
            case "toString" -> "StubIModInfo[" + modId + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
        return (IModInfo) Proxy.newProxyInstance(
                IModInfo.class.getClassLoader(),
                new Class<?>[] { IModInfo.class },
                h);
    }
}

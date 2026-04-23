package io.github.mclibprovider.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforgespi.language.IModInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit test for the {@link McLibModContainer} wrapper. Proves the return type is a real
 * {@link ModContainer} (so NeoForge's cast succeeds), carries the wrapped instance, and publishes
 * a live {@link IEventBus}.
 * <p>
 * {@link IModInfo} is mocked via a dynamic proxy because the real implementations live in the
 * NeoForge runtime we don't have on the test classpath. Only the methods {@link ModContainer}'s
 * constructor calls ({@code getModId}, {@code getNamespace}) need to return values.
 */
class McLibModContainerTest {

    @Test
    void wrapperExposesInstanceAndEventBus() {
        IModInfo info = stubInfo("my_mod", "my_mod");
        Object instance = new Object();

        McLibModContainer container = new McLibModContainer(info, instance);

        assertInstanceOf(ModContainer.class, container, "must be a ModContainer for NeoForge's cast");
        assertSame(instance, container.getModInstance());
        assertEquals("my_mod", container.getModId());
        IEventBus bus = container.getEventBus();
        assertNotNull(bus, "event bus must be provided for NeoForge's mod event dispatch");
    }

    @Test
    void constructModIsNoOp() {
        IModInfo info = stubInfo("m", "m");
        McLibModContainer container = new McLibModContainer(info, new Object());
        // Invoked via reflection because constructMod() is protected.
        try {
            Method m = ModContainer.class.getDeclaredMethod("constructMod");
            m.setAccessible(true);
            m.invoke(container);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("constructMod should be invokable and not throw", e);
        }
    }

    private static IModInfo stubInfo(String modId, String namespace) {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "getModId" -> modId;
            case "getNamespace" -> namespace;
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

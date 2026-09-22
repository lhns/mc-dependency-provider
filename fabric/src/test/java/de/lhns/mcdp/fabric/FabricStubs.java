package de.lhns.mcdp.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dynamic-proxy stand-ins for the three fabric-loader interfaces the adapter touches. Only the
 * methods the adapter calls are answered; default methods run their real body (so
 * {@code ModContainer.findPath} walks {@code getRootPaths()} exactly as fabric-loader's own
 * {@code ModContainerImpl} does, which does not override it); anything else throws, so a new
 * dependency on the loader surface shows up as a test failure rather than a silent null.
 */
final class FabricStubs {

    private FabricStubs() {}

    static ModContainer mod(String modId, Path... rootPaths) {
        ModMetadata metadata = proxy(ModMetadata.class, Map.of("getId", args -> modId));
        List<Path> roots = List.of(rootPaths);
        return proxy(ModContainer.class, Map.of(
                "getMetadata", args -> metadata,
                "getRootPaths", args -> roots));
    }

    static FabricLoader loader(ModContainer... mods) {
        List<ModContainer> all = List.of(mods);
        return proxy(FabricLoader.class, Map.of(
                "getAllMods", args -> all,
                "getModContainer", args -> {
                    for (ModContainer m : all) {
                        if (m.getMetadata().getId().equals(args[0])) return Optional.of(m);
                    }
                    return Optional.empty();
                }));
    }

    interface Answer {
        Object answer(Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> iface, Map<String, Answer> answers) {
        InvocationHandler handler = (self, method, args) -> {
            Answer a = answers.get(method.getName());
            if (a != null) return a.answer(args);
            if (method.isDefault()) return InvocationHandler.invokeDefault(self, method, args);
            return objectMethod(iface, self, method, args);
        };
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static Object objectMethod(Class<?> iface, Object self, Method method, Object[] args) {
        switch (method.getName()) {
            case "toString": return iface.getSimpleName() + "Stub";
            case "hashCode": return System.identityHashCode(self);
            case "equals": return self == args[0];
            default: throw new UnsupportedOperationException(
                    iface.getSimpleName() + "." + method.getName() + " is not stubbed");
        }
    }
}

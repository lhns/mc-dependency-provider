package de.lhns.mcdp.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Scala: first try to return the singleton {@code MODULE$} static field (for {@code object Foo});
 * if the target is a regular {@code class}, fall back to constructor-arity dispatch.
 */
public final class ScalaEntrypointAdapter implements EntrypointAdapter {

    @Override
    public Object construct(Class<?> entryClass, Object... args) throws ReflectiveOperationException {
        Object singleton = scalaModuleInstance(entryClass);
        if (singleton != null) return singleton;
        // Scala compiles `object Foo` to a forwarder class `Foo` plus a companion class
        // `Foo$` carrying the MODULE$ singleton. Fabric/NeoForge entrypoints are usually
        // declared against the forwarder name, so look up the companion by suffixing `$`.
        try {
            Class<?> companion = Class.forName(entryClass.getName() + "$", true, entryClass.getClassLoader());
            Object companionSingleton = scalaModuleInstance(companion);
            if (companionSingleton != null) return companionSingleton;
        } catch (ClassNotFoundException ignored) {
            // fall through to constructor-arity dispatch
        }
        return new JavaEntrypointAdapter().construct(entryClass, args);
    }

    static Object scalaModuleInstance(Class<?> cls) throws ReflectiveOperationException {
        Field field;
        try {
            field = cls.getDeclaredField("MODULE$");
        } catch (NoSuchFieldException notModule) {
            return null;
        }
        if (!Modifier.isStatic(field.getModifiers())) return null;
        field.setAccessible(true);
        return field.get(null);
    }
}

package io.github.mclibprovider.core;

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

package io.github.mclibprovider.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Kotlin: first try to return the singleton {@code INSTANCE} static field (for {@code object Foo});
 * if the target is a regular {@code class}, fall back to constructor-arity dispatch.
 */
public final class KotlinEntrypointAdapter implements EntrypointAdapter {

    @Override
    public Object construct(Class<?> entryClass, Object... args) throws ReflectiveOperationException {
        Object singleton = kotlinObjectInstance(entryClass);
        if (singleton != null) return singleton;
        return new JavaEntrypointAdapter().construct(entryClass, args);
    }

    static Object kotlinObjectInstance(Class<?> cls) throws ReflectiveOperationException {
        Field field;
        try {
            field = cls.getDeclaredField("INSTANCE");
        } catch (NoSuchFieldException notObject) {
            return null;
        }
        if (!Modifier.isStatic(field.getModifiers())) return null;
        field.setAccessible(true);
        return field.get(null);
    }
}

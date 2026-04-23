package io.github.mclibprovider.core;

import java.lang.reflect.Constructor;

/**
 * Constructor-arity dispatch. Tries {@code (args[0..N])} for N = args.length down to 0, returning
 * the first match.
 */
public final class JavaEntrypointAdapter implements EntrypointAdapter {

    @Override
    public Object construct(Class<?> entryClass, Object... args) throws ReflectiveOperationException {
        for (int arity = args.length; arity >= 0; arity--) {
            Object[] slice = new Object[arity];
            System.arraycopy(args, 0, slice, 0, arity);
            Constructor<?> ctor = findMatchingCtor(entryClass, slice);
            if (ctor != null) {
                ctor.setAccessible(true);
                return ctor.newInstance(slice);
            }
        }
        throw new NoSuchMethodException("No matching constructor on " + entryClass.getName()
                + " for arities " + args.length + " down to 0");
    }

    static Constructor<?> findMatchingCtor(Class<?> cls, Object[] args) {
        outer:
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != args.length) continue;
            for (int i = 0; i < params.length; i++) {
                if (args[i] == null) {
                    if (params[i].isPrimitive()) continue outer;
                } else if (!params[i].isAssignableFrom(args[i].getClass())) {
                    continue outer;
                }
            }
            return ctor;
        }
        return null;
    }
}

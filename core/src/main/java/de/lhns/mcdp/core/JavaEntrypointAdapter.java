package de.lhns.mcdp.core;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Constructor dispatch by <em>bag of context arguments</em>. The platform adapter passes a bag of
 * context objects ({@code IModInfo}, {@code IEventBus}, {@code Dist}, …); for each declared
 * constructor on the entry class, the adapter tries to fill its parameters by type from the bag,
 * using each bag entry at most once. The most-specific (highest-arity) match wins.
 * <p>
 * This means a mod author can write any of:
 * <ul>
 *   <li>{@code MyMod()} — no-arg, ignores everything in the bag.</li>
 *   <li>{@code MyMod(IEventBus bus)} — FML default {@code @Mod}-class signature.</li>
 *   <li>{@code MyMod(IModInfo info)} — original mcdepprovider single-arg shape.</li>
 *   <li>{@code MyMod(IModInfo info, IEventBus bus)} — both, in either order.</li>
 *   <li>{@code MyMod(IEventBus bus, Dist dist)} — typical FML extra.</li>
 * </ul>
 * No declared parameter ordering — parameters are matched on type identity, not position. Two
 * constructor parameters with the same type are unsupported (the bag would not know which arg
 * goes where); in practice the context bag never has duplicate types.
 */
public final class JavaEntrypointAdapter implements EntrypointAdapter {

    @Override
    public Object construct(Class<?> entryClass, Object... args) throws ReflectiveOperationException {
        Constructor<?>[] ctors = entryClass.getDeclaredConstructors();
        // Sort by parameter count descending so the most-specific ctor that can be filled wins.
        Arrays.sort(ctors, Comparator.comparingInt((Constructor<?> c) -> c.getParameterCount()).reversed());

        for (Constructor<?> ctor : ctors) {
            Object[] resolved = fillFromBag(ctor.getParameterTypes(), args);
            if (resolved != null) {
                ctor.setAccessible(true);
                return ctor.newInstance(resolved);
            }
        }
        throw new NoSuchMethodException("No matching constructor on " + entryClass.getName()
                + " for context-bag types " + describeTypes(args));
    }

    /**
     * For each parameter, pick a bag entry whose runtime type is assignable to the parameter type.
     * Each bag entry can be used at most once. Returns the filled argument array, or {@code null}
     * if any parameter cannot be filled.
     */
    static Object[] fillFromBag(Class<?>[] paramTypes, Object[] bag) {
        Object[] resolved = new Object[paramTypes.length];
        boolean[] used = new boolean[bag.length];
        for (int i = 0; i < paramTypes.length; i++) {
            int pick = -1;
            for (int j = 0; j < bag.length; j++) {
                if (used[j] || bag[j] == null) continue;
                if (paramTypes[i].isAssignableFrom(bag[j].getClass())) {
                    pick = j;
                    break;
                }
            }
            if (pick < 0) return null;
            resolved[i] = bag[pick];
            used[pick] = true;
        }
        return resolved;
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

    private static String describeTypes(Object[] args) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return sb.append("]").toString();
    }
}

package de.lhns.mcdp.neoforge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Function;

/**
 * Test plumbing shared by the three NeoForge trees' suites. Touches no FML type, so it compiles
 * against loader 3.0.45, 4.0.42 and 10.0.36 alike.
 *
 * <p>The loader suites reach {@code McdpLanguageLoader}'s static seams through {@link #call}
 * rather than by name. That is deliberate: those suites must also <em>compile</em> against the
 * adapter as it stood before the fixes they pin (private methods, a missing seam), so each bug
 * can be shown failing at run time with its real error — on 1.20.6, a
 * {@code NoSuchMethodError} from the SPI the adapter was compiled against — instead of as a
 * compile error. A renamed seam fails its test loudly, with {@code NoSuchMethodException}.
 */
final class NeoForgeTestSupport {

    private NeoForgeTestSupport() {}

    /**
     * A dynamic proxy for {@code type} that answers exactly the methods named in
     * {@code answers}. Default methods it does not name run their real body; anything else
     * throws, so a stub never silently hands {@code null} to code that did not expect the call.
     */
    static <T> T stub(Class<T> type, Map<String, Function<Object[], Object>> answers) {
        InvocationHandler h = (proxy, method, args) -> {
            Function<Object[], Object> answer = answers.get(method.getName());
            if (answer != null) return answer.apply(args == null ? new Object[0] : args);
            switch (method.getName()) {
                case "toString":
                    if (method.getParameterCount() == 0) return "Stub" + type.getSimpleName();
                    break;
                case "hashCode":
                    if (method.getParameterCount() == 0) return System.identityHashCode(proxy);
                    break;
                case "equals":
                    if (method.getParameterCount() == 1) return proxy == args[0];
                    break;
                default:
                    break;
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            throw new UnsupportedOperationException(
                    "unstubbed call " + type.getSimpleName() + "." + method.getName());
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, h));
    }

    /**
     * Invoke the static method {@code owner.name(params) -> returns} whatever its visibility,
     * rethrowing what the method itself threw.
     *
     * <p>A method handle, not {@code Class.getDeclaredMethod}: the latter materialises every
     * declared method's signature, so a class holding one method whose signature names a type
     * absent at run time fails as a whole with {@code NoClassDefFoundError}. That is exactly the
     * shape of the pre-fix 1.20.6 loader (a lambda taking neoforgespi 8.0.4's
     * {@code IModLanguageProvider}), and it would hide the {@code NoSuchMethodError} FML itself
     * hits. A handle resolves the one method, the way a call site does.
     */
    static Object call(Class<?> owner, String name, Class<?> returns, Class<?>[] params,
                       Object... args) {
        MethodHandle handle;
        try {
            handle = MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
                    .findStatic(owner, name, MethodType.methodType(returns, params));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError("no seam " + owner.getSimpleName() + "." + name, e);
        }
        return invoke(handle, args);
    }

    /** Call the no-arg accessor {@code target.accessor() -> returns}, whatever its visibility. */
    static Object read(Object target, String accessor, Class<?> returns) {
        MethodHandle handle;
        try {
            handle = MethodHandles.privateLookupIn(target.getClass(), MethodHandles.lookup())
                    .findVirtual(target.getClass(), accessor, MethodType.methodType(returns));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError("no accessor " + accessor + " on " + target.getClass(), e);
        }
        return invoke(handle.bindTo(target));
    }

    private static Object invoke(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable checked) {
            throw new IllegalStateException(checked);
        }
    }
}

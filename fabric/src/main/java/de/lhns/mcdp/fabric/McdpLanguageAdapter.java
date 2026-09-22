package de.lhns.mcdp.fabric;

import de.lhns.mcdp.api.McdpProvider;
import de.lhns.mcdp.core.EntrypointAdapter;
import de.lhns.mcdp.core.ModClassLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fabric's {@link LanguageAdapter} that dispatches into {@link EntrypointAdapter}.
 * <p>
 * Fabric calls {@code create(container, value, type)} for each entrypoint declared with
 * {@code "adapter": "mcdepprovider"}. By that time {@link McdpPreLaunch} has already populated
 * the {@link ModClassLoader} for this mod, so we:
 * <ol>
 *   <li>look up the mod's loader via {@link McdpProvider#loaderFor(String)};</li>
 *   <li>load the entrypoint class through it — isolating the mod's Maven deps;</li>
 *   <li>construct via the language-specific adapter.</li>
 * </ol>
 * <p>
 * {@code value} takes the same forms as fabric-loader's own {@code DefaultLanguageAdapter}:
 * <ul>
 *   <li>{@code com.x.Foo} — an instance of {@code Foo} (for {@code lang = "kotlin"} /
 *       {@code "scala"}, the {@code object}'s singleton);</li>
 *   <li>{@code com.x.Foo::field} — the value of a static field;</li>
 *   <li>{@code com.x.Foo::method} — a proxy of the requested interface type that calls the
 *       method. An instance method is bound to the same object the plain form would produce, so
 *       a Kotlin or Scala {@code object}'s method runs on its singleton;</li>
 *   <li>{@code com.x.Foo::<init>} — a proxy whose method constructs a new {@code Foo}
 *       (fabric-loader 0.19+).</li>
 * </ul>
 */
public final class McdpLanguageAdapter implements LanguageAdapter {

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        String modId = mod.getMetadata().getId();
        ModClassLoader loader = McdpProvider.loaderFor(modId);
        if (loader == null) {
            throw new LanguageAdapterException(
                    "mcdepprovider: ModClassLoader for '" + modId + "' not initialized."
                            + " Ensure McdpPreLaunch ran and the mod declares its manifest.");
        }

        String[] methodSplit = value.split("::");
        if (methodSplit.length >= 3) {
            throw fail(modId, "Invalid handle format: " + value, null);
        }
        String className = methodSplit[0];

        String lang = McdpPreLaunch.langFor(modId);

        Class<?> entryClass;
        try {
            entryClass = Class.forName(className, true, loader);
        } catch (ClassNotFoundException e) {
            throw fail(modId, "entrypoint class not found: " + className, e);
        }

        // An unknown manifest `lang` is IllegalArgumentException from forLang. Fabric reports
        // only LanguageAdapterException with the mod it belongs to, so an unwrapped one
        // surfaced as a bare crash naming neither the mod nor mcdepprovider.
        EntrypointAdapter adapter;
        try {
            adapter = EntrypointAdapter.forLang(lang);
        } catch (IllegalArgumentException e) {
            throw new LanguageAdapterException("mcdepprovider: unsupported lang '" + lang
                    + "' in the manifest of '" + modId + "' (entrypoint " + value + ")", e);
        }

        if (methodSplit.length == 2) {
            return createFromMember(modId, lang, adapter, entryClass, methodSplit[1], value, type);
        }

        // Fabric entrypoints are usually no-arg objects or classes with a no-arg ctor. Our
        // EntrypointAdapter falls back to the 0-arg constructor after any singleton check.
        Object instance = construct(modId, adapter, entryClass, value);

        if (!type.isInstance(instance)) {
            throw fail(modId, value + " (lang=" + lang + ") does not implement " + type.getName(), null);
        }
        return type.cast(instance);
    }

    /**
     * {@code Class::member}, following fabric-loader's {@code DefaultLanguageAdapter} rule for
     * rule — same lookups, same order, same checks — except that an instance method is bound to
     * {@link EntrypointAdapter#construct}'s object rather than a fresh {@code new Foo()}: a
     * Kotlin {@code object}'s constructor is private, and its methods belong to {@code INSTANCE}.
     */
    private static <T> T createFromMember(String modId, String lang, EntrypointAdapter adapter,
                                          Class<?> c, String name, String value, Class<T> type)
            throws LanguageAdapterException {
        List<Executable> executables = new ArrayList<>();
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) executables.add(m);
        }
        if (name.equals("<init>")) {
            executables.addAll(Arrays.asList(c.getDeclaredConstructors()));
        }

        Field field;
        try {
            field = c.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            field = null;
        }
        if (field != null) {
            if (!Modifier.isStatic(field.getModifiers())) {
                throw fail(modId, "Field " + value + " must be static!", null);
            }
            if (!executables.isEmpty()) {
                throw fail(modId, "Ambiguous " + value + " - refers to both field and method!", null);
            }
            if (!type.isAssignableFrom(field.getType())) {
                throw fail(modId, "Field " + value + " cannot be cast to " + type.getName() + "!", null);
            }
            try {
                return type.cast(field.get(null));
            } catch (IllegalAccessException e) {
                throw fail(modId, "Field " + value + " cannot be accessed!", e);
            }
        }

        if (!type.isInterface()) {
            throw fail(modId, "Cannot proxy method " + value + " to non-interface type " + type.getName() + "!", null);
        }
        if (executables.isEmpty()) {
            throw fail(modId, "Could not find " + value + "!", null);
        }
        if (executables.size() >= 2) {
            throw fail(modId, "Found multiple method entries of name " + value + "!", null);
        }

        Executable target = executables.get(0);
        MethodHandle handle;
        try {
            handle = target instanceof Method m
                    ? MethodHandles.lookup().unreflect(m)
                    : MethodHandles.lookup().unreflectConstructor((Constructor<?>) target);
        } catch (IllegalAccessException e) {
            throw fail(modId, "Cannot access " + value + "!", e);
        }

        if (target instanceof Method m && !Modifier.isStatic(m.getModifiers())) {
            // The receiver is what the plain `Foo` form would return: INSTANCE for a Kotlin
            // object, MODULE$ for a Scala one, otherwise a new instance.
            Object receiver = construct(modId, adapter, c, value + " (lang=" + lang + ")");
            if (!c.isInstance(receiver)) {
                // The Scala adapter answers a plain class that has a companion object with the
                // companion's MODULE$, which has none of the class's instance methods.
                throw fail(modId, "Cannot bind instance method " + value + ": lang=" + lang
                        + " produced " + (receiver == null ? "null" : "a " + receiver.getClass().getName())
                        + ", not a " + c.getName(), null);
            }
            handle = handle.bindTo(receiver);
        }

        try {
            return MethodHandleProxies.asInterfaceInstance(type, handle);
        } catch (IllegalArgumentException | WrongMethodTypeException e) {
            throw fail(modId, "Cannot proxy " + value + " as " + type.getName() + "!", e);
        }
    }

    private static Object construct(String modId, EntrypointAdapter adapter, Class<?> c, String what)
            throws LanguageAdapterException {
        try {
            return adapter.construct(c);
        } catch (ReflectiveOperationException e) {
            throw fail(modId, "failed to instantiate " + what, e);
        }
    }

    /** Every failure names mcdepprovider and the mod: Fabric's own report may not. */
    private static LanguageAdapterException fail(String modId, String message, Throwable cause) {
        String full = "mcdepprovider: " + message + " (mod '" + modId + "')";
        return cause == null ? new LanguageAdapterException(full) : new LanguageAdapterException(full, cause);
    }
}

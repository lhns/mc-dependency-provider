package io.github.mclibprovider.core;

/**
 * Language-specific "given a Class, produce an instance" adapter. The provider picks an
 * implementation based on the manifest's {@code lang} field. See ADR-0005.
 * <p>
 * All implementations accept up to three Object arguments to allow per-platform ctor-arity
 * dispatch (e.g. NeoForge: {@code (IEventBus, ModContainer, Dist)}; Fabric: arity-0). Unused
 * arguments should be passed as {@code null} and ignored by the adapter if its target ctor
 * doesn't accept them.
 */
public interface EntrypointAdapter {

    /**
     * Construct (or return a singleton of) {@code entryClass}, trying the provided constructor
     * arguments in decreasing arity. Returns the mod-instance object.
     */
    Object construct(Class<?> entryClass, Object... args) throws ReflectiveOperationException;

    /** Resolve by name: {@code "java"}, {@code "scala"}, or {@code "kotlin"}. */
    static EntrypointAdapter forLang(String lang) {
        return switch (lang) {
            case "java" -> new JavaEntrypointAdapter();
            case "scala" -> new ScalaEntrypointAdapter();
            case "kotlin" -> new KotlinEntrypointAdapter();
            default -> throw new IllegalArgumentException("unsupported lang: " + lang);
        };
    }
}

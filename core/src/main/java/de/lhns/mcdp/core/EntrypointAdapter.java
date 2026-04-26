package de.lhns.mcdp.core;

/**
 * Language-specific "given a Class, produce an instance" adapter. The provider picks an
 * implementation based on the manifest's {@code lang} field. See ADR-0005.
 * <p>
 * Platform adapters pass a <em>bag of context objects</em> — typically
 * {@code (IModInfo, IEventBus, Dist)} on NeoForge, empty on Fabric. The adapter scans the entry
 * class's declared constructors and picks the most-specific one whose parameter types can all be
 * filled from the bag (matched by type, in any order). Unused bag entries are simply ignored, so
 * existing arity-0 / single-arg entry classes keep working alongside FML's standard
 * {@code @Mod}-class shape.
 */
public interface EntrypointAdapter {

    /**
     * Construct (or return a singleton of) {@code entryClass}. {@code args} is the context bag —
     * order is not significant; matching is by type. Returns the mod-instance object.
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

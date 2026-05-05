package de.lhns.mcdp.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * DSL for the automatic bridge codegen (ADR-0018, ADR-0021). Nested under
 * {@link McdpProviderExtension#getBridges()}. All properties have project-derived defaults
 * (see {@link McdpProviderPlugin}); the typical case needs zero explicit config.
 *
 * <p>The codegen seeds on classes whose class-level annotations match
 * {@link #getBridgedAnnotations()} — mixins, NeoForge {@code @EventBusSubscriber} subscribers,
 * and any other annotation-driven side-load. Per-class bytecode is rewritten so cross-classloader
 * call sites dispatch through bridge interfaces (defined in
 * {@link #getBridgePackage() bridgePackage}, shared) whose impls live in the sibling
 * {@code <bridgePackage>_impl} package and are loaded by the per-mod {@code ModClassLoader}.
 */
public abstract class BridgeCodegenExtension {

    /**
     * Master switch. Default {@code true} — codegen is on for every project that applies the
     * plugin. Set to {@code false} to skip the codegen task entirely and use the hand-written
     * {@code @McdpMixin} bridge pattern (ADR-0008) instead.
     */
    public abstract Property<Boolean> getEnabled();

    /**
     * Package the codegen emits bridge interfaces into. Auto-added to
     * {@link McdpProviderExtension#getSharedPackages()}. Bridge impls go in the sibling
     * {@code <bridgePackage>_impl} package, which is intentionally NOT in
     * {@code sharedPackages} so the per-mod {@code ModClassLoader} child-loads them
     * (ADR-0021 errata).
     * <p>
     * Default: {@code <group>.<projectName>.mcdp_bridges}.
     */
    public abstract Property<String> getBridgePackage();

    /**
     * Class-level annotation FQNs the codegen seeds on. Any class on the mod's compile output
     * carrying one of these annotations becomes a candidate for bytecode rewriting if it
     * touches Scala/Kotlin/mod-private code.
     * <p>
     * Default (set in {@code McdpProviderPlugin}):
     * <ul>
     *   <li>{@code org.spongepowered.asm.mixin.Mixin}</li>
     *   <li>{@code net.neoforged.bus.api.EventBusSubscriber}</li>
     * </ul>
     * <p>
     * See ADR-0021. The FQN list is config-driven so users can pin against a specific NeoForge
     * version (the {@code @EventBusSubscriber} package may rename across major versions) and add
     * project-specific annotations driving custom registries.
     */
    public abstract ListProperty<String> getBridgedAnnotations();

    /**
     * Class-level annotation FQNs marking types that are <em>injected into a different
     * classloader</em> at runtime — typically Mixin accessors merged into vanilla types by
     * Sponge Mixin. These types end up loaded by both the per-mod {@code ModClassLoader}
     * (when mod code references them) and the game-layer transformer (where Mixin merges
     * them), producing two distinct {@code Class} objects with the same FQN. Mod-side casts
     * to such types fail with {@code ClassCastException} unless the type's package is also
     * in {@link McdpProviderExtension#getSharedPackages()}.
     * <p>
     * The validator described in ADR-0024 walks the mod's non-annotated classes for references
     * to types annotated with any FQN in this list and fails the build if the target package
     * is not shared.
     * <p>
     * Default (set in {@code McdpProviderPlugin}): {@code org.spongepowered.asm.mixin.Mixin}.
     * Distinct from {@link #getBridgedAnnotations()} — {@code @EventBusSubscriber} triggers
     * bridge codegen for a different reason (NeoForge invokes static handlers from another
     * thread, but doesn't merge the class into another loader's namespace).
     * <p>
     * Override per project to add custom annotation-driven side-loads (Architectury
     * {@code @ExpectPlatform}, custom codegen frameworks).
     */
    public abstract ListProperty<String> getCrossLoaderAnnotations();
}

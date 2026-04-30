package de.lhns.mcdp.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * DSL for the automatic Mixin → mod-private bridge codegen (ADR-0018). Nested under
 * {@link McdpProviderExtension#getMixinBridges()}. All properties have project-derived defaults
 * (see {@link McdpProviderPlugin}); the typical case needs zero explicit config.
 */
public abstract class MixinBridgesExtension {

    /**
     * Master switch. Default {@code true} — codegen is on for every project that applies the
     * plugin. Set to {@code false} to skip the codegen task entirely and use the hand-written
     * {@code @McdpMixin} bridge pattern (ADR-0008) instead.
     */
    public abstract Property<Boolean> getEnabled();

    /**
     * Package the codegen emits bridges into. Auto-added to
     * {@link McdpProviderExtension#getSharedPackages()}.
     * <p>
     * Default: {@code <group>.<projectName>.mcdp_mixin_bridges}.
     */
    public abstract Property<String> getBridgePackage();

    /**
     * Class-level annotation FQNs whose bytecode the codegen should seed on (in addition to the
     * existing {@code *.mixins.json} seed). The codegen is no longer Sponge-Mixin-specific:
     * any class FML/Sponge will define from the mod jar — mixins, {@code @EventBusSubscriber}
     * registrar targets, custom annotation-driven side-loads — needs cross-classloader call
     * sites rewritten through bridges if it touches Scala/Kotlin/mod-private code.
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
}

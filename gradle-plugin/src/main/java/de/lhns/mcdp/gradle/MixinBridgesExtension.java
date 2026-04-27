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
     * Glob-ish package prefixes whose types are <em>not</em> visible to the game-layer (mixin)
     * classloader and must therefore be bridged. Each entry is a literal prefix (no wildcards
     * needed; {@code com.example.} matches {@code com.example.foo.Bar}).
     * <p>
     * Default: a single prefix derived from the project's {@code group}. For most mods that's
     * correct; override if your mod ships some types under a shared namespace already on
     * {@link McdpProviderExtension#getSharedPackages()}.
     */
    public abstract ListProperty<String> getModPrivatePackages();

    /**
     * Package the codegen emits bridges into. Auto-added to
     * {@link McdpProviderExtension#getSharedPackages()}.
     * <p>
     * Default: {@code <group>.<projectName>.mcdp_mixin_bridges}.
     */
    public abstract Property<String> getBridgePackage();
}

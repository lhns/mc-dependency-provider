package de.lhns.mcdp.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * Project-level DSL extension for the {@code de.lhns.mcdp} plugin.
 *
 * <pre>{@code
 * dependencies {
 *     // Loom / ModDevGradle deps stay where they live (modImplementation,
 *     // implementation, etc.) — they're platform-provided and never enter the manifest.
 *     modImplementation("net.fabricmc:fabric-loader:0.16.9")
 *
 *     // Deps the mod wants delivered through mcdepprovider's per-mod classloader
 *     // go on the dedicated bucket. They're transitively resolved and emitted into
 *     // META-INF/mcdepprovider.toml.
 *     mcdepImplementation("org.typelevel:cats-core_3:2.13.0")
 *     mcdepImplementation("io.circe:circe-parser_3:0.14.10")
 * }
 *
 * mcdepprovider {
 *     lang.set("scala")
 *     sharedPackages.add("com.example.api")
 * }
 * }</pre>
 */
public abstract class McdpProviderExtension {

    private final MixinBridgesExtension mixinBridges;

    @Inject
    public McdpProviderExtension(ObjectFactory objects) {
        this.mixinBridges = objects.newInstance(MixinBridgesExtension.class);
    }

    /** One of {@code java}, {@code scala}, {@code kotlin}. */
    public abstract Property<String> getLang();

    /**
     * Package prefixes the per-mod {@code ModClassLoader} delegates parent-first instead of
     * child-first. Required for any class that must be the same {@code Class} object on both
     * sides of the loader boundary — almost exclusively bridge interfaces for Mixin (ADR-0008).
     * <p>
     * The automatic Mixin bridge codegen (see {@link #getMixinBridges()}, ADR-0018) emits its
     * generated bridge package and adds it to this list automatically. Manual entries are only
     * needed for the cases codegen does not handle (interface injection, mod-private mixin
     * superclasses, hand-written bridges via {@code @McdpMixin}). See
     * {@code docs/mixin-bridge.md}.
     */
    public abstract ListProperty<String> getSharedPackages();

    /**
     * Configuration for automatic Mixin → mod-private bridge codegen (ADR-0018). The codegen
     * walks each compiled mixin class, identifies cross-classloader references, emits a bridge
     * interface in a shared-package, emits a per-mod forwarding impl, and rewrites the mixin's
     * method bodies to dispatch through the bridge. Enabled by default; opt out with
     * {@code mixinBridges { enabled.set(false) }} to use the hand-written {@code @McdpMixin}
     * pattern instead.
     */
    public MixinBridgesExtension getMixinBridges() {
        return mixinBridges;
    }

    /**
     * Configures the nested {@code mixinBridges} block from a Groovy/Kotlin DSL closure.
     */
    public void mixinBridges(org.gradle.api.Action<? super MixinBridgesExtension> action) {
        action.execute(mixinBridges);
    }

    /**
     * Names of run tasks (ModDevGradle's {@code runClient} / {@code runServer} etc., or Fabric
     * Loom's equivalents) whose classpath should be stripped of any jar that also appears in the
     * generated manifest. This is the dev-mode parity hook from ADR-0007: once stripped, the
     * provider's {@code ManifestConsumer} + {@code ModClassLoader} pipeline is the only route by
     * which deps reach the mod at runtime, exactly as in production.
     * <p>
     * Defaults to the standard task names on both platforms. Projects that use non-standard run
     * task names should override this explicitly.
     */
    public abstract ListProperty<String> getPatchRunTasks();
}

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

    private final BridgeCodegenExtension bridges;

    @Inject
    public McdpProviderExtension(ObjectFactory objects) {
        this.bridges = objects.newInstance(BridgeCodegenExtension.class);
    }

    /** One of {@code java}, {@code scala}, {@code kotlin}. */
    public abstract Property<String> getLang();

    /**
     * Package prefixes the per-mod {@code ModClassLoader} delegates parent-first instead of
     * child-first. Required for any class that must be the same {@code Class} object on both
     * sides of the loader boundary — almost exclusively bridge interfaces (ADR-0008).
     * <p>
     * The automatic bridge codegen (see {@link #getBridges()}, ADR-0018/0021) emits its
     * generated bridge package and adds it to this list automatically. Manual entries are only
     * needed for the cases codegen does not handle (interface injection, mod-private mixin
     * superclasses, hand-written bridges via {@code @McdpMixin}). See
     * {@code docs/bridges.md}.
     */
    public abstract ListProperty<String> getSharedPackages();

    /**
     * Configuration for the automatic bridge codegen (ADR-0018, ADR-0021). The codegen walks
     * each compiled class whose annotations match {@code bridgedAnnotations}, identifies
     * cross-classloader references, emits a bridge interface in the shared bridge package,
     * emits a per-mod forwarding impl in the sibling {@code _impl} package, and rewrites
     * method bodies to dispatch through the bridge. Enabled by default; opt out with
     * {@code bridges { enabled.set(false) }} to use the hand-written {@code @McdpMixin}
     * pattern instead.
     */
    public BridgeCodegenExtension getBridges() {
        return bridges;
    }

    /**
     * Configures the nested {@code bridges} block from a Groovy/Kotlin DSL closure.
     */
    public void bridges(org.gradle.api.Action<? super BridgeCodegenExtension> action) {
        action.execute(bridges);
    }

    /**
     * Names of run tasks (ModDevGradle's {@code runClient} / {@code runServer} etc., or Fabric
     * Loom's equivalents) whose classpath should be stripped of any jar that also appears in the
     * generated manifest. This is the dev-mode parity hook from ADR-0007: once stripped, the
     * provider's {@code ManifestConsumer} + {@code ModClassLoader} pipeline becomes the only
     * route by which deps reach the mod at runtime — useful for verifying that a mod truly
     * works through the per-mod isolation path.
     * <p>
     * <strong>Defaults to empty</strong> (no stripping). The original "strip everything by
     * default" behavior assumed mod classes are routed through mcdp's {@code ModClassLoader}
     * even in dev, but ModDevGradle 2.0.91+ puts them on FML's GAME-layer transformer
     * regardless. Mod code that references Scala/Kotlin stdlib at link time during early
     * world-gen paths can't reach those libs through the GAME layer's classloader chain —
     * stripping them breaks the mod. Opt in only when intentionally testing prod-parity:
     * <pre>
     * mcdepprovider {
     *     patchRunTasks.set(["runServer"])  // strict isolation test, not normal dev
     * }
     * </pre>
     */
    public abstract ListProperty<String> getPatchRunTasks();
}

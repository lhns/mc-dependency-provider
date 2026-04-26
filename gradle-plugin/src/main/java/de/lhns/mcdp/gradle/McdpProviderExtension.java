package de.lhns.mcdp.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Project-level DSL extension for the {@code de.lhns.mcdp} plugin.
 *
 * <pre>{@code
 * dependencies {
 *     // Loom / ModDevGradle deps stay where they live (modImplementation,
 *     // implementation, etc.) — they're platform-provided and never enter the manifest.
 *     modImplementation("net.fabricmc:fabric-loader:0.16.9")
 *
 *     // Deps the mod wants delivered through mc-lib-provider's per-mod classloader
 *     // go on the dedicated bucket. They're transitively resolved and emitted into
 *     // META-INF/mclibprovider.toml.
 *     mcLibImplementation("org.typelevel:cats-core_3:2.13.0")
 *     mcLibImplementation("io.circe:circe-parser_3:0.14.10")
 * }
 *
 * mclibprovider {
 *     lang.set("scala")
 *     sharedPackages.add("com.example.api")
 * }
 * }</pre>
 */
public abstract class McdpProviderExtension {

    /** One of {@code java}, {@code scala}, {@code kotlin}. */
    public abstract Property<String> getLang();

    /**
     * Package prefixes the per-mod {@code ModClassLoader} delegates parent-first instead of
     * child-first. Required for any class that must be the same {@code Class} object on both
     * sides of the loader boundary — almost exclusively bridge interfaces for Mixin (ADR-0008).
     */
    public abstract ListProperty<String> getSharedPackages();

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

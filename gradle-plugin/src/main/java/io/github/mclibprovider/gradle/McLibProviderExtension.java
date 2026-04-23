package io.github.mclibprovider.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Project-level DSL extension for the {@code io.github.mclibprovider} plugin.
 *
 * <pre>{@code
 * mclibprovider {
 *     lang.set("scala")
 *     sharedPackages.add("com.example.api")
 *     excludeGroup("net.neoforged")
 *     excludeGroup("com.mojang")
 * }
 * }</pre>
 */
public abstract class McLibProviderExtension {

    /** One of {@code java}, {@code scala}, {@code kotlin}. */
    public abstract Property<String> getLang();

    /** Package prefixes to include in {@code shared_packages} in the generated manifest. */
    public abstract ListProperty<String> getSharedPackages();

    /** {@code groupId:artifactId} patterns to drop from the manifest (platform-provided libs). */
    public abstract ListProperty<String> getExclusions();

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

    /** Shortcut: exclude every artifact with the given {@code groupId}. Emits {@code group:*}. */
    public void excludeGroup(String group) {
        getExclusions().add(group + ":*");
    }

    /** Shortcut: exclude a specific {@code groupId:artifactId}. */
    public void exclude(String groupAndArtifact) {
        getExclusions().add(groupAndArtifact);
    }
}

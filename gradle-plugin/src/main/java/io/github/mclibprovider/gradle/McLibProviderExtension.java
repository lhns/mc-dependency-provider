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

    /** Shortcut: exclude every artifact with the given {@code groupId}. Emits {@code group:*}. */
    public void excludeGroup(String group) {
        getExclusions().add(group + ":*");
    }

    /** Shortcut: exclude a specific {@code groupId:artifactId}. */
    public void exclude(String groupAndArtifact) {
        getExclusions().add(groupAndArtifact);
    }
}

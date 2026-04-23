package io.github.mclibprovider.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.bundling.Jar;

import java.util.List;

/**
 * Entry point for the {@code io.github.mclibprovider} plugin.
 * <p>
 * Wires two tasks per project:
 * <ul>
 *   <li>{@code generateMcLibManifest} — reads the project's {@code runtimeClasspath} and writes
 *       {@code META-INF/mc-jvm-mod.toml} into {@code processResources} output, which is then
 *       packaged into the mod jar.</li>
 *   <li>{@code prepareMcLibDevCache} — hard-links the project's locally-resolved jars into the
 *       shared library cache so dev run-tasks exercise the provider's production dep-loading path
 *       without touching the network (ADR-0007).</li>
 * </ul>
 * The classpath-surgery task that strips manifest-listed deps from {@code runClient} is out of
 * scope for this plugin at v0.1 — mod authors are expected to split their dependencies into
 * {@code mcLibManifest} (provided at runtime by mc-lib-provider) and the NeoForge/Fabric
 * run-task classpath explicitly. That keeps us uncoupled from ModDevGradle/Loom internals.
 */
public final class McLibProviderPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        McLibProviderExtension ext = project.getExtensions()
                .create("mclibprovider", McLibProviderExtension.class);
        ext.getLang().convention("java");
        ext.getSharedPackages().convention(List.of());
        ext.getExclusions().convention(List.of(
                "net.minecraft:*",
                "net.neoforged:*",
                "net.fabricmc:*",
                "com.mojang:*",
                "io.github.mclibprovider:*"));

        // Locate the project's runtimeClasspath. Mod authors apply `java-library` (or similar),
        // which creates it. The task is lazily configured so we don't force resolution at
        // configuration-time.
        project.getPluginManager().withPlugin("java", applied -> {
            JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
            SourceSet main = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            Configuration runtimeClasspath = project.getConfigurations()
                    .getByName(main.getRuntimeClasspathConfigurationName());

            var generate = project.getTasks().register(
                    "generateMcLibManifest", GenerateMcLibManifestTask.class, t -> {
                        t.setGroup("mc-lib-provider");
                        t.setDescription("Emits META-INF/mc-jvm-mod.toml from runtimeClasspath.");
                        t.getLang().set(ext.getLang());
                        t.getSharedPackages().set(ext.getSharedPackages());
                        t.getExclusions().set(ext.getExclusions());
                        t.getOutputFile().set(project.getLayout().getBuildDirectory()
                                .file("mc-lib-provider/META-INF/mc-jvm-mod.toml"));

                        // Resolve lazily — only when the task actually runs.
                        t.getResolvedArtifactFiles().from(runtimeClasspath);
                        t.getRepositoryUrls().set(project.provider(() ->
                                GenerateMcLibManifestTask.collectRepositoryUrls(project.getRepositories())));
                        t.getArtifactCoords().set(project.provider(() ->
                                GenerateMcLibManifestTask.collectArtifactCoords(runtimeClasspath)));
                    });

            project.getTasks().register(
                    "prepareMcLibDevCache", PrepareDevCacheTask.class, t -> {
                        t.setGroup("mc-lib-provider");
                        t.setDescription("Hard-links resolved jars into the shared mc-lib-provider cache.");
                        t.dependsOn(generate);
                        t.getManifestFile().set(generate.flatMap(GenerateMcLibManifestTask::getOutputFile));
                        t.getArtifactFiles().from(runtimeClasspath);
                    });

            // Bake the generated manifest into the packaged jar's META-INF.
            project.getTasks().withType(Jar.class).configureEach(jarTask -> {
                jarTask.dependsOn(generate);
                jarTask.from(generate.flatMap(g -> g.getOutputFile().map(f -> f.getAsFile().getParentFile().getParentFile())),
                        copy -> copy.include("META-INF/mc-jvm-mod.toml"));
            });
        });
    }
}

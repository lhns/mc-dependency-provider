package io.github.mclibprovider.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.language.jvm.tasks.ProcessResources;

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
        ext.getPatchRunTasks().convention(List.of(
                "runClient",
                "runServer",
                "runGameTestServer",
                "runData"));

        // Locate the project's runtimeClasspath. Mod authors apply `java-library` (or similar),
        // which creates it. The task is lazily configured so we don't force resolution at
        // configuration-time.
        project.getPluginManager().withPlugin("java", applied -> {
            JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
            SourceSet main = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

            // A dedicated resolvable configuration that captures *only* the mod author's
            // declared library dependencies plus their transitive closure. In Loom / ModDevGradle
            // projects, runtimeClasspath includes the entire Minecraft game classpath
            // (guava, gson, icu4j, oshi, mixinextras, the game jar itself). Those are
            // platform-provided and must never land in the mod's manifest. We extend only
            // from the standard bucket configurations the mod author declares into.
            Configuration mcLibManifest = project.getConfigurations().maybeCreate("mcLibManifest");
            mcLibManifest.setCanBeResolved(true);
            mcLibManifest.setCanBeConsumed(false);
            mcLibManifest.setVisible(false);
            for (String bucket : List.of("api", "implementation", "runtimeOnly")) {
                Configuration src = project.getConfigurations().findByName(bucket);
                if (src != null) mcLibManifest.extendsFrom(src);
            }
            Configuration runtimeClasspath = mcLibManifest;

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

            // Bake the generated manifest into processResources output so it's on the
            // runtime classpath in both production (inside the jar) and dev mode (inside
            // build/resources/main, which Loom/ModDevGradle use for runServer/runClient).
            project.getTasks().named("processResources", ProcessResources.class,
                    resources -> {
                        resources.dependsOn(generate);
                        resources.from(generate.flatMap(g -> g.getOutputFile().map(f -> f.getAsFile().getParentFile().getParentFile())),
                                copy -> copy.include("META-INF/mc-jvm-mod.toml"));
                    });

            // ADR-0007 dev-mode parity: strip manifest-listed jars from run task classpaths.
            RunTaskClasspathPatch.apply(project, generate, ext.getPatchRunTasks());
        });
    }
}

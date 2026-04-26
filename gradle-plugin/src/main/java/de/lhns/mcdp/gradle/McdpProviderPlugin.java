package de.lhns.mcdp.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.language.jvm.tasks.ProcessResources;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Entry point for the {@code de.lhns.mcdp} plugin.
 * <p>
 * Wires three things per project:
 * <ul>
 *   <li>The {@code mcLibImplementation} dependency bucket — opt-in config for libs that should be
 *       served through mcdepprovider's per-mod classloader at runtime. Anything declared here
 *       (plus its transitive closure) lands in the generated manifest. Anything declared on
 *       {@code implementation}, {@code modImplementation}, etc. stays platform-provided and is
 *       never written to the manifest. {@code implementation} extends from this bucket so deps
 *       declared as {@code mcLibImplementation} are still on the compile classpath.</li>
 *   <li>{@code generateMcdpManifest} — walks the resolved {@code mcLibImplementation} closure,
 *       subtracts any artifact whose {@code group:name} is also resolved through
 *       {@code runtimeClasspath} via a non-mclib path (i.e. genuinely platform-provided), and
 *       writes {@code META-INF/mcdepprovider.toml} into {@code processResources} output.</li>
 *   <li>{@code prepareMcdpDevCache} — hard-links the resolved jars into the shared cache so dev
 *       run-tasks exercise the production dep-loading path without a network hit (ADR-0007).</li>
 * </ul>
 */
public final class McdpProviderPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        McdpProviderExtension ext = project.getExtensions()
                .create("mcdepprovider", McdpProviderExtension.class);
        ext.getLang().convention("java");
        ext.getSharedPackages().convention(List.of());
        ext.getPatchRunTasks().convention(List.of(
                "runClient",
                "runServer",
                "runGameTestServer",
                "runData"));

        project.getPluginManager().withPlugin("java", applied -> {
            JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
            SourceSet main = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

            // mcLibImplementation: declarable dep bucket — opt-in. Users place deps they want
            // served by mcdepprovider here. Extends nothing; carries no transitive contributions
            // from `implementation`, so platform-provided deps (Loom's modImplementation, MDG's
            // NeoForge stack) never leak into the manifest.
            Configuration mcLibImplementation = project.getConfigurations().maybeCreate("mcLibImplementation");
            mcLibImplementation.setCanBeResolved(false);
            mcLibImplementation.setCanBeConsumed(false);
            mcLibImplementation.setVisible(false);

            // Make mcLibImplementation deps available on the compile + runtime classpath in dev so
            // sources compile against them and Loom/MDG see them as ordinary jars (RunTaskClasspath-
            // Patch then strips them at run-time, restoring prod parity per ADR-0007).
            Configuration implementation = project.getConfigurations().findByName("implementation");
            if (implementation != null) implementation.extendsFrom(mcLibImplementation);

            // Resolvable view of mcLibImplementation. The manifest task and the dev-cache task
            // both consume this — never runtimeClasspath, so platform jars are guaranteed absent.
            Configuration mcLibManifest = project.getConfigurations().maybeCreate("mcLibManifest");
            mcLibManifest.setCanBeResolved(true);
            mcLibManifest.setCanBeConsumed(false);
            mcLibManifest.setVisible(false);
            mcLibManifest.extendsFrom(mcLibImplementation);

            // Resolvable view of the compile/runtime classpath excluding the mclib bucket. Used by
            // the manifest task to detect "already provided by the platform" artifacts and skip
            // them — handles the rare case where a mod declares `mcLibImplementation("X")` but X
            // is also a transitive of some Loom-/MDG-provided platform jar. Same `group:name`
            // appearing on both sides means the platform already serves it; no need to redeclare.
            Configuration platformView = project.getConfigurations().maybeCreate("mcLibPlatformView");
            platformView.setCanBeResolved(true);
            platformView.setCanBeConsumed(false);
            platformView.setVisible(false);
            for (String bucket : List.of("api", "implementation", "runtimeOnly", "compileOnly")) {
                Configuration src = project.getConfigurations().findByName(bucket);
                if (src != null) platformView.extendsFrom(src);
            }

            var generate = project.getTasks().register(
                    "generateMcdpManifest", GenerateMcdpManifestTask.class, t -> {
                        t.setGroup("mcdepprovider");
                        t.setDescription("Emits META-INF/mcdepprovider.toml from mcLibImplementation.");
                        t.getLang().set(ext.getLang());
                        t.getSharedPackages().set(ext.getSharedPackages());
                        t.getOutputFile().set(project.getLayout().getBuildDirectory()
                                .file("mcdepprovider/META-INF/mcdepprovider.toml"));

                        t.getResolvedArtifactFiles().from(mcLibManifest);
                        t.getRepositoryUrls().set(project.provider(() ->
                                GenerateMcdpManifestTask.collectRepositoryUrls(project.getRepositories())));
                        t.getArtifactCoords().set(project.provider(() ->
                                GenerateMcdpManifestTask.collectArtifactCoords(mcLibManifest)));
                        // Set-difference: anything in platformView that's NOT also in
                        // mcLibImplementation's closure is platform-provided. Subtract those.
                        t.getPlatformProvidedKeys().set(project.provider(() -> {
                            Set<String> mclibKeys = new HashSet<>();
                            for (var coord : GenerateMcdpManifestTask.collectArtifactCoords(mcLibManifest)) {
                                mclibKeys.add(coord.group() + ":" + coord.name());
                            }
                            Set<String> platformKeys = new HashSet<>();
                            for (var coord : GenerateMcdpManifestTask.collectArtifactCoords(platformView)) {
                                String key = coord.group() + ":" + coord.name();
                                if (!mclibKeys.contains(key)) platformKeys.add(key);
                            }
                            // Always exclude mcdepprovider's own artifacts — they're shipped by
                            // the platform adapter jar, not via the manifest.
                            platformKeys.add("de.lhns.mcdp:core");
                            platformKeys.add("de.lhns.mcdp:deps-lib");
                            platformKeys.add("de.lhns.mcdp:fabric");
                            platformKeys.add("de.lhns.mcdp:neoforge");
                            return List.copyOf(platformKeys);
                        }));
                    });

            var prepareCache = project.getTasks().register(
                    "prepareMcdpDevCache", PrepareDevCacheTask.class, t -> {
                        t.setGroup("mcdepprovider");
                        t.setDescription("Hard-links resolved jars into the shared mcdepprovider cache.");
                        t.dependsOn(generate);
                        t.getManifestFile().set(generate.flatMap(GenerateMcdpManifestTask::getOutputFile));
                        t.getArtifactFiles().from(mcLibManifest);
                    });

            project.afterEvaluate(p -> {
                List<String> runNames = ext.getPatchRunTasks().getOrElse(List.of());
                for (String name : runNames) {
                    var existing = project.getTasks().findByName(name);
                    if (existing != null) existing.dependsOn(prepareCache);
                }
            });

            project.getTasks().named("processResources", ProcessResources.class,
                    resources -> {
                        resources.dependsOn(generate);
                        resources.from(generate.flatMap(g -> g.getOutputFile().map(f -> f.getAsFile().getParentFile().getParentFile())),
                                copy -> copy.include("META-INF/mcdepprovider.toml"));
                    });

            RunTaskClasspathPatch.apply(project, generate, ext.getPatchRunTasks());
        });
    }
}

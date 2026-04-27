package de.lhns.mcdp.gradle;

import de.lhns.mcdp.gradle.mixinbridges.BridgeCodegenTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.language.jvm.tasks.ProcessResources;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Entry point for the {@code de.lhns.mcdp} plugin.
 * <p>
 * Wires three things per project:
 * <ul>
 *   <li>The {@code mcdepImplementation} dependency bucket — opt-in config for libs that should be
 *       served through mcdepprovider's per-mod classloader at runtime. Anything declared here
 *       (plus its transitive closure) lands in the generated manifest. Anything declared on
 *       {@code implementation}, {@code modImplementation}, etc. stays platform-provided and is
 *       never written to the manifest. {@code implementation} extends from this bucket so deps
 *       declared as {@code mcdepImplementation} are still on the compile classpath.</li>
 *   <li>{@code generateMcdpManifest} — walks the resolved {@code mcdepImplementation} closure,
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

        // Mixin-bridge codegen defaults. Compute per-project so the typical mod author writes
        // zero `mixinBridges {}` config: bridgePackage = <group>.<projectName>.mcdp_mixin_bridges,
        // modPrivatePackages = [<group>.] (every type under the project's group is bridge-eligible).
        ext.getMixinBridges().getEnabled().convention(true);
        ext.getMixinBridges().getBridgePackage().convention(project.provider(() -> {
            String group = String.valueOf(project.getGroup());
            if (group.isEmpty() || "unspecified".equals(group)) return "";
            String name = project.getName().replace('-', '_');
            return group + "." + name + ".mcdp_mixin_bridges";
        }));
        ext.getMixinBridges().getModPrivatePackages().convention(project.provider(() -> {
            String group = String.valueOf(project.getGroup());
            return (group.isEmpty() || "unspecified".equals(group)) ? List.of() : List.of(group + ".");
        }));

        // Auto-register the bridge package on sharedPackages so the per-mod ModClassLoader
        // delegates the generated interface parent-first. Skipped when no project group is set
        // (typical for test fixtures) so we never inject ad-hoc entries the user can't see.
        ext.getSharedPackages().addAll(project.provider(() -> {
            if (!Boolean.TRUE.equals(ext.getMixinBridges().getEnabled().getOrElse(true))) {
                return List.of();
            }
            String pkg = ext.getMixinBridges().getBridgePackage().getOrNull();
            return (pkg == null || pkg.isEmpty()) ? List.<String>of() : List.of(pkg + ".");
        }));

        project.getPluginManager().withPlugin("java", applied -> {
            JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
            SourceSet main = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

            // mcdepImplementation: declarable dep bucket — opt-in. Users place deps they want
            // served by mcdepprovider here. Extends nothing; carries no transitive contributions
            // from `implementation`, so platform-provided deps (Loom's modImplementation, MDG's
            // NeoForge stack) never leak into the manifest.
            Configuration mcdepImplementation = project.getConfigurations().maybeCreate("mcdepImplementation");
            mcdepImplementation.setCanBeResolved(false);
            mcdepImplementation.setCanBeConsumed(false);
            mcdepImplementation.setVisible(false);

            // Make mcdepImplementation deps available on the compile + runtime classpath in dev so
            // sources compile against them and Loom/MDG see them as ordinary jars (RunTaskClasspath-
            // Patch then strips them at run-time, restoring prod parity per ADR-0007).
            Configuration implementation = project.getConfigurations().findByName("implementation");
            if (implementation != null) implementation.extendsFrom(mcdepImplementation);

            // Resolvable view of mcdepImplementation. The manifest task and the dev-cache task
            // both consume this — never runtimeClasspath, so platform jars are guaranteed absent.
            Configuration mcdepManifest = project.getConfigurations().maybeCreate("mcdepManifest");
            mcdepManifest.setCanBeResolved(true);
            mcdepManifest.setCanBeConsumed(false);
            mcdepManifest.setVisible(false);
            mcdepManifest.extendsFrom(mcdepImplementation);

            // Resolvable view of the compile/runtime classpath excluding the mclib bucket. Used by
            // the manifest task to detect "already provided by the platform" artifacts and skip
            // them — handles the rare case where a mod declares `mcdepImplementation("X")` but X
            // is also a transitive of some Loom-/MDG-provided platform jar. Same `group:name`
            // appearing on both sides means the platform already serves it; no need to redeclare.
            Configuration platformView = project.getConfigurations().maybeCreate("mcdepPlatformView");
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
                        t.setDescription("Emits META-INF/mcdepprovider.toml from mcdepImplementation.");
                        t.getLang().set(ext.getLang());
                        t.getSharedPackages().set(ext.getSharedPackages());
                        t.getOutputFile().set(project.getLayout().getBuildDirectory()
                                .file("mcdepprovider/META-INF/mcdepprovider.toml"));

                        t.getResolvedArtifactFiles().from(mcdepManifest);
                        t.getRepositoryUrls().set(project.provider(() ->
                                GenerateMcdpManifestTask.collectRepositoryUrls(project.getRepositories())));
                        t.getArtifactCoords().set(project.provider(() ->
                                GenerateMcdpManifestTask.collectArtifactCoords(mcdepManifest)));
                        // Set-difference: anything in platformView that's NOT also in
                        // mcdepImplementation's closure is platform-provided. Subtract those.
                        t.getPlatformProvidedKeys().set(project.provider(() -> {
                            Set<String> mclibKeys = new HashSet<>();
                            for (var coord : GenerateMcdpManifestTask.collectArtifactCoords(mcdepManifest)) {
                                mclibKeys.add(coord.group() + ":" + coord.name());
                            }
                            Set<String> platformKeys = new HashSet<>();
                            for (var coord : GenerateMcdpManifestTask.collectArtifactCoords(platformView)) {
                                String key = coord.group() + ":" + coord.name();
                                if (!mclibKeys.contains(key)) platformKeys.add(key);
                            }
                            // Always exclude mcdepprovider's own artifacts — they're shipped by
                            // the unified mcdp jar, not via the manifest. The legacy
                            // `mcdp-fabric`/`mcdp-neoforge` coords are still recognized in case
                            // a downstream consumer pins them via a substitution that bypasses
                            // the unified `mcdp` artifact.
                            platformKeys.add("de.lhns.mcdp:core");
                            platformKeys.add("de.lhns.mcdp:deps-lib");
                            platformKeys.add("de.lhns.mcdp:mcdp");
                            platformKeys.add("de.lhns.mcdp:mcdp-fabric");
                            platformKeys.add("de.lhns.mcdp:mcdp-neoforge");
                            return List.copyOf(platformKeys);
                        }));
                    });

            var prepareCache = project.getTasks().register(
                    "prepareMcdpDevCache", PrepareDevCacheTask.class, t -> {
                        t.setGroup("mcdepprovider");
                        t.setDescription("Hard-links resolved jars into the shared mcdepprovider cache.");
                        t.dependsOn(generate);
                        t.getManifestFile().set(generate.flatMap(GenerateMcdpManifestTask::getOutputFile));
                        t.getArtifactFiles().from(mcdepManifest);
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

            registerMixinBridgeCodegen(project, ext, main);
        });
    }

    /**
     * Wires {@code generateMcdpMixinBridges}: scans compiled mixin classes (declared in
     * {@code src/main/resources/*.mixins.json}), rewrites their bytecode in place under
     * {@code build/mcdp-mixin-bridges/classes/}, and produces matching bridge interfaces +
     * impls + a manifest tree under {@code build/mcdp-mixin-bridges/resources/}. The
     * rewritten classes are layered onto the jar so they win over the original ones.
     */
    private void registerMixinBridgeCodegen(Project project, McdpProviderExtension ext, SourceSet main) {
        var bridgeTask = project.getTasks().register(
                "generateMcdpMixinBridges", BridgeCodegenTask.class, t -> {
                    t.setGroup("mcdepprovider");
                    t.setDescription("Auto-generates Mixin → mod-private bridges (ADR-0018).");
                    t.onlyIf(self -> {
                        if (!Boolean.TRUE.equals(ext.getMixinBridges().getEnabled().getOrElse(true))) {
                            return false;
                        }
                        // Skip when there are no compiled classes — a project applying the plugin
                        // for manifest generation alone (no Java/Scala/Kotlin source) shouldn't
                        // be forced through codegen.
                        File classes = main.getJava().getDestinationDirectory().getAsFile().get();
                        return classes.isDirectory();
                    });
                    t.getCompiledClassesDir().fileProvider(
                            project.provider(() -> main.getJava().getDestinationDirectory()
                                    .getAsFile().get()));
                    t.getBridgePackage().set(ext.getMixinBridges().getBridgePackage());
                    t.getSharedPackages().set(ext.getSharedPackages());
                    t.getMixinConfigFiles().from(project.provider(() -> {
                        List<File> matches = new ArrayList<>();
                        for (File r : main.getResources().getSrcDirs()) {
                            if (!r.isDirectory()) continue;
                            File[] children = r.listFiles((dir, name) -> name.endsWith("mixins.json"));
                            if (children != null) {
                                for (File c : children) matches.add(c);
                            }
                        }
                        return matches;
                    }));
                    t.getOutputClassesDir().set(project.getLayout().getBuildDirectory()
                            .dir("mcdp-mixin-bridges/classes"));
                    t.getManifestOutputDir().set(project.getLayout().getBuildDirectory()
                            .dir("mcdp-mixin-bridges/resources"));
                    t.getReportFile().set(project.getLayout().getBuildDirectory()
                            .file("mcdp-mixin-bridges/report.txt"));
                    var compileJava = project.getTasks().findByName("compileJava");
                    if (compileJava != null) t.dependsOn(compileJava);
                    var compileScala = project.getTasks().findByName("compileScala");
                    if (compileScala != null) t.dependsOn(compileScala);
                    var compileKotlin = project.getTasks().findByName("compileKotlin");
                    if (compileKotlin != null) t.dependsOn(compileKotlin);
                });

        // Layer the rewritten classes onto every Jar task. Set duplicatesStrategy=INCLUDE so the
        // bridge output (added after the Jar plugin's default from(main.output)) overwrites the
        // original compiled mixins in the final archive.
        project.getTasks().withType(Jar.class).configureEach(jar -> {
            jar.dependsOn(bridgeTask);
            jar.setDuplicatesStrategy(org.gradle.api.file.DuplicatesStrategy.INCLUDE);
            jar.from(bridgeTask.flatMap(BridgeCodegenTask::getOutputClassesDir));
            jar.from(bridgeTask.flatMap(BridgeCodegenTask::getManifestOutputDir));
        });
        // Also feed processResources so dev-mode runs (Loom/MDG) pick up the META-INF
        // manifests without rebuilding the jar; rewritten classes flow into runtime classpath
        // through SourceSet.output below.
        project.getTasks().named("processResources", ProcessResources.class, resources -> {
            resources.dependsOn(bridgeTask);
            resources.setDuplicatesStrategy(org.gradle.api.file.DuplicatesStrategy.INCLUDE);
            resources.from(bridgeTask.flatMap(BridgeCodegenTask::getManifestOutputDir));
        });
        // Add bridge classes to the main source set output so dev runs and downstream
        // dependents see rewritten mixin classes + emitted bridge interfaces / impls.
        main.getOutput().dir(java.util.Map.of("builtBy", bridgeTask),
                bridgeTask.flatMap(BridgeCodegenTask::getOutputClassesDir));
    }
}

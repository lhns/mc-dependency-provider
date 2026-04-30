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
        // Default: no stripping. ADR-0007's original "strip manifest jars from run tasks for
        // dev-prod parity" assumed mod classes are loaded only through mcdp's ModClassLoader
        // even in dev. ModDevGradle 2.0.91+ doesn't honor that — mod classes go on FML's
        // GAME-layer transformer regardless, and need their compile-time deps (Scala stdlib,
        // etc) visible on that classloader chain. Stripping them breaks any mod whose code
        // references Scala/Kotlin/etc at link time during world generation or other early
        // game-layer paths. Users who want strict prod-parity dev runs can opt in via
        // `mcdepprovider.patchRunTasks = ["runServer", ...]` — explicit choice, not default.
        ext.getPatchRunTasks().convention(List.of());

        // Bridge codegen defaults. Compute per-project so the typical mod author writes zero
        // `bridges {}` config: bridgePackage = <group>.<projectName>.mcdp_bridges. Bridge impls
        // go in the sibling <bridgePackage>_impl package (ADR-0021 errata).
        ext.getBridges().getEnabled().convention(true);
        ext.getBridges().getBridgePackage().convention(project.provider(() -> {
            String group = String.valueOf(project.getGroup());
            if (group.isEmpty() || "unspecified".equals(group)) return "";
            String name = project.getName().replace('-', '_');
            return group + "." + name + ".mcdp_bridges";
        }));
        // Annotation seed (ADR-0021). Default covers Sponge-Mixin and NeoForge's automatic event
        // subscriber registrar — the two FML/Sponge side-loads documented as leak-prone in
        // mc-fluid-physics. Override per project to pin to a different NeoForge version's
        // annotation FQN or to add custom annotation-driven side-loads.
        ext.getBridges().getBridgedAnnotations().convention(List.of(
                "org.spongepowered.asm.mixin.Mixin",
                "net.neoforged.bus.api.EventBusSubscriber"
        ));

        // Auto-register the bridge package on sharedPackages so the per-mod ModClassLoader
        // delegates the generated interface parent-first. Skipped when no project group is set
        // (typical for test fixtures) so we never inject ad-hoc entries the user can't see.
        // Note: only the bridge interface package is auto-shared; the sibling _impl package is
        // intentionally NOT shared so ModClassLoader child-loads impls (ADR-0021 errata).
        ext.getSharedPackages().addAll(project.provider(() -> {
            if (!Boolean.TRUE.equals(ext.getBridges().getEnabled().getOrElse(true))) {
                return List.of();
            }
            String pkg = ext.getBridges().getBridgePackage().getOrNull();
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

            // Note: we deliberately do NOT auto-wire mcdepImplementation deps into MDG's
            // RunModel.getAdditionalRuntimeClasspathConfiguration(). MDG's additional runtime
            // classpath is JPMS-strict — it walks each jar to compute a module descriptor.
            // Scala/cats jars contain packages with reserved-keyword names
            // (e.g. cats.kernel.instances.byte) that fail JPMS validation immediately at
            // BootstrapLauncher init, before mod loading even starts. ADR-0002 covers this:
            // those libs must live on an unnamed-module classpath (which mcdp's per-mod
            // ModClassLoader provides). Mod code that needs Scala/Kotlin stdlib at link time
            // outside the per-mod ModClassLoader (e.g. mixin bodies that don't go through the
            // bridge) is a separate problem with no clean cross-MDG-version fix.

            var generate = project.getTasks().register(
                    "generateMcdpManifest", GenerateMcdpManifestTask.class, t -> {
                        t.setGroup("mcdepprovider");
                        t.setDescription("Emits META-INF/mcdepprovider.toml from mcdepImplementation.");
                        t.getLang().set(ext.getLang());
                        t.getSharedPackages().set(ext.getSharedPackages());
                        t.getOutputFile().set(project.getLayout().getBuildDirectory()
                                .file("mcdepprovider/META-INF/mcdepprovider.toml"));
                        // Source-set output dirs the runtime adapter should add to the per-mod
                        // ModClassLoader's URL list when this manifest is read in dev mode.
                        // Captures every entry the build tool already knows about (compile
                        // outputs + resources + bridge codegen output) so the adapter doesn't
                        // have to walk the filesystem looking for `build/<lang>/main` siblings.
                        // Absolute paths are harmless in shipped jars: the runtime checks each
                        // path with Files.isDirectory before using it, so paths that don't
                        // exist on the consumer's machine are silently ignored.
                        t.getDevRoots().set(project.provider(() -> {
                            java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>();
                            for (File f : main.getOutput().getFiles()) {
                                roots.add(f.getAbsolutePath());
                            }
                            // Belt-and-suspenders: explicitly include the bridge codegen output.
                            // main.output.dir(...) registers it via the SourceSetOutput, but
                            // Loom/MDG can re-root the source set after our plugin's apply
                            // block runs, dropping the registration before this provider is
                            // queried. Without this dir on the per-mod ModClassLoader's URL
                            // list, bridge impls fall through to the parent loader and pull
                            // mod-private types (Scala stdlib, mod's own classes) onto the
                            // wrong classloader. Idempotent (LinkedHashSet dedupes).
                            roots.add(project.getLayout().getBuildDirectory()
                                    .dir("mcdp-bridges/classes").get().getAsFile().getAbsolutePath());
                            return new ArrayList<>(roots);
                        }));

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

            registerBridgeCodegen(project, ext, main);
        });
    }

    /**
     * Wires {@code generateMcdpBridges}: scans compiled classes whose annotations match
     * {@code bridgedAnnotations} (default: {@code @Mixin}, {@code @EventBusSubscriber}),
     * rewrites their bytecode in place under {@code build/mcdp-bridges/classes/}, and
     * produces matching bridge interfaces + impls + a manifest tree under
     * {@code build/mcdp-bridges/resources/}. The rewritten classes are layered onto the jar
     * so they win over the original ones.
     */
    private void registerBridgeCodegen(Project project, McdpProviderExtension ext, SourceSet main) {
        var bridgeTask = project.getTasks().register(
                "generateMcdpBridges", BridgeCodegenTask.class, t -> {
                    t.setGroup("mcdepprovider");
                    t.setDescription("Auto-generates cross-classloader bridges (ADR-0018, ADR-0021).");
                    t.onlyIf(self -> {
                        if (!Boolean.TRUE.equals(ext.getBridges().getEnabled().getOrElse(true))) {
                            return false;
                        }
                        // Skip when no compile task produced any classes — a project applying
                        // the plugin for manifest generation alone (no Java/Scala/Kotlin source)
                        // shouldn't be forced through codegen. Walks every output dir in the
                        // SourceSet so multi-language projects (Scala/Kotlin joint compilation
                        // that wipes the empty compileJava output) are still detected.
                        for (File f : main.getOutput().getClassesDirs().getFiles()) {
                            if (f.isDirectory()) return true;
                        }
                        return false;
                    });
                    // Feed every compile-task output dir into the scanner. .java mixins
                    // joint-compiled by scalac live under the scala output dir; pure-Java mixins
                    // under java; Kotlin joint output under kotlin. The scanner picks the first
                    // dir containing the seeded class.
                    t.getCompiledClassesDirs().from(main.getOutput().getClassesDirs());
                    // Consumer's compile classpath — fed only to ASM's COMPUTE_FRAMES loader so
                    // common-supertype computation can resolve Minecraft (and any other
                    // off-plugin) types that mixin bytecode pushes across branch joins.
                    t.getRuntimeFrameLookupClasspath().from(main.getCompileClasspath());
                    t.getBridgePackage().set(ext.getBridges().getBridgePackage());
                    t.getSharedPackages().set(ext.getSharedPackages());
                    t.getBridgedAnnotations().set(ext.getBridges().getBridgedAnnotations());
                    // Class-file version for emitted bridges = project's targetCompatibility
                    // mapped via JavaVersion ordinal + 44 (Java 8 = 52, Java 21 = 65). Reading at
                    // configure time inside the closure keeps the value lazy w.r.t. user overrides.
                    t.getClassFileVersion().set(project.provider(() -> {
                        JavaPluginExtension je = project.getExtensions().findByType(JavaPluginExtension.class);
                        if (je == null) return org.objectweb.asm.Opcodes.V21;
                        org.gradle.api.JavaVersion tc = je.getTargetCompatibility();
                        if (tc == null) return org.objectweb.asm.Opcodes.V21;
                        return tc.ordinal() + 45;
                    }));
                    t.getOutputClassesDir().set(project.getLayout().getBuildDirectory()
                            .dir("mcdp-bridges/classes"));
                    t.getManifestOutputDir().set(project.getLayout().getBuildDirectory()
                            .dir("mcdp-bridges/resources"));
                    t.getReportFile().set(project.getLayout().getBuildDirectory()
                            .file("mcdp-bridges/report.txt"));
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

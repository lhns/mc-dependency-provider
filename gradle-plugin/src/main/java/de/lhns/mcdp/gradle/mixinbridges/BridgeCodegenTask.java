package de.lhns.mcdp.gradle.mixinbridges;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Walks every class on {@link #getCompiledClassesDirs()} whose class-level annotations match
 * {@link #getBridgedAnnotations()}, runs the scan + rewrite pipeline, and writes back rewritten
 * {@code .class} files plus generated bridge interface and impl class files into a dedicated
 * output directory. The output is layered onto {@code processResources}/{@code jar} so the
 * rewritten classes win over the original ones.
 *
 * <p>The unified {@code META-INF/mcdp-bridges.toml} manifest (ADR-0019) is written into
 * {@link #getManifestOutputDir()} so it gets picked up by {@code processResources} as source
 * for the META-INF tree.</p>
 */
public abstract class BridgeCodegenTask extends DefaultTask {

    /**
     * All class-output directories produced by the project's compile tasks (java, scala, kotlin).
     * The scanner searches every directory for each declared mixin FQN and uses the first match
     * in iteration order. Multi-language SourceSets (e.g. Scala or Kotlin joint compilation of
     * {@code .java} mixins co-located with {@code .scala}/{@code .kt} sources) need every output
     * dir scanned because the canonical bytecode for a given mixin lives wherever the polyglot
     * compiler wrote it.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getCompiledClassesDirs();

    /**
     * Consumer's compile classpath. Consulted only by ASM's {@code COMPUTE_FRAMES} pass when it
     * needs to compute a common supertype between two reference types referenced in mixin
     * bytecode. Without this, MC types like {@code FluidState}/{@code BlockState} aren't visible
     * to the gradle-plugin classloader and frame computation throws CNF. May be empty for tests
     * or projects whose mixins reference no off-classpath types.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getRuntimeFrameLookupClasspath();

    @Input
    public abstract Property<String> getBridgePackage();

    @Input
    public abstract ListProperty<String> getSharedPackages();

    /**
     * Class-level annotation FQNs that seed the codegen.
     * See {@link de.lhns.mcdp.gradle.BridgeCodegenExtension#getBridgedAnnotations()} (ADR-0021).
     */
    @Input
    public abstract ListProperty<String> getBridgedAnnotations();

    /**
     * Class-file major version emitted for generated bridge interface + impl + lambda wrapper
     * classes. Defaults to 65 (Java 21 = {@code Opcodes.V21}); the plugin overrides this from the
     * project's {@code targetCompatibility} so emitted bridges match the rest of the mod's
     * bytecode.
     */
    @Input
    public abstract Property<Integer> getClassFileVersion();

    @OutputDirectory
    public abstract DirectoryProperty getOutputClassesDir();

    @OutputDirectory
    public abstract DirectoryProperty getManifestOutputDir();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void run() throws IOException {
        Logger log = getLogger();
        List<Path> inDirs = new ArrayList<>();
        for (var f : getCompiledClassesDirs().getFiles()) {
            if (f.isDirectory()) inDirs.add(f.toPath());
        }
        Path outClassesDir = getOutputClassesDir().get().getAsFile().toPath();
        Path manifestDir = getManifestOutputDir().get().getAsFile().toPath();
        Path manifestFile = manifestDir.resolve("META-INF").resolve("mcdp-bridges.toml");

        cleanDirectory(outClassesDir);
        cleanDirectory(manifestDir);
        Files.createDirectories(manifestFile.getParent());

        // Annotation-driven seed (ADR-0021): walks every compiled .class on the input dirs and
        // selects classes whose class-level annotation table intersects the configured FQN list.
        // Default list (set in McdpProviderPlugin) covers @Mixin and @EventBusSubscriber.
        List<String> annoFqns = getBridgedAnnotations().getOrElse(List.of());
        Set<String> seedFqns;
        if (annoFqns.isEmpty()) {
            seedFqns = new LinkedHashSet<>();
        } else {
            seedFqns = new LinkedHashSet<>(new AnnotationSeedScanner(annoFqns).scan(inDirs));
        }
        if (seedFqns.isEmpty()) {
            log.info("mcdp-bridges: no classes carry a configured bridgedAnnotation "
                    + "— nothing to do.");
            writeReport(getReportFile().get().getAsFile().toPath(), List.of(), List.of(), List.of());
            return;
        }

        BridgePolicy policy = new BridgePolicy(
                getSharedPackages().getOrElse(List.of()),
                getBridgePackage().get());
        BridgeMixinScanner scanner = new BridgeMixinScanner(policy);

        // ASM frame-computation loader: consumer's compile classpath ∪ this project's class
        // outputs, parented at platform so we don't leak gradle-plugin internals.
        List<java.net.URL> urls = new ArrayList<>();
        for (var f : getRuntimeFrameLookupClasspath().getFiles()) {
            urls.add(f.toURI().toURL());
        }
        for (var f : getCompiledClassesDirs().getFiles()) {
            urls.add(f.toURI().toURL());
        }
        try (java.net.URLClassLoader frameLookup = new java.net.URLClassLoader(
                urls.toArray(new java.net.URL[0]), ClassLoader.getPlatformClassLoader())) {

        int cfv = getClassFileVersion().getOrElse(org.objectweb.asm.Opcodes.V21);
        MixinRewriter rewriter = new MixinRewriter(policy, getBridgePackage().get(), frameLookup);
        BridgeInterfaceEmitter ifaceEmitter = new BridgeInterfaceEmitter(getBridgePackage().get(), cfv);
        BridgeImplEmitter implEmitter = new BridgeImplEmitter(getBridgePackage().get(), frameLookup, cfv);
        LambdaWrapperEmitter lambdaEmitter = new LambdaWrapperEmitter(getBridgePackage().get(), frameLookup, cfv);

        // Aggregated bridges across the whole compilation: we want one bridge interface per
        // target type even if multiple mixins reference it.
        Map<String, List<BridgeMember>> aggregated = new LinkedHashMap<>();
        List<String> rewrittenMixins = new ArrayList<>();
        // Per-mixin target map preserved for the unified TOML manifest emission below.
        Map<String, Map<String, List<BridgeMember>>> perMixinTargets = new LinkedHashMap<>();
        // Per-mixin lambda artifacts — emitted as separate class files and registered in the
        // manifest via the same [[bridge]] schema as regular bridges (ADR-0021).
        Map<String, List<LambdaWrapperEmitter.Artifacts>> perMixinLambdas = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String fqn : seedFqns) {
            String relPath = BridgePolicy.toInternal(fqn) + ".class";
            Path classFile = null;
            for (Path inDir : inDirs) {
                Path candidate = inDir.resolve(relPath);
                if (Files.isRegularFile(candidate)) { classFile = candidate; break; }
            }
            if (classFile == null) {
                warnings.add("mcdp-bridges: seeded class " + fqn
                        + " not found in any compiled-classes dir " + inDirs
                        + " — skipping. (Was the build clean?)");
                continue;
            }
            byte[] bytes = Files.readAllBytes(classFile);
            MixinScanResult result = scanner.scan(bytes);
            warnings.addAll(result.warnings());
            switch (result.status()) {
                case UNSUPPORTED -> {
                    errors.addAll(result.errors());
                }
                case SKIPPED -> {
                    log.info("mcdp-bridges: " + fqn + " has no cross-classloader refs.");
                }
                case REWRITABLE -> {
                    // Lambda sites: emit one bridge interface + impl pair per site, write
                    // their .class files, and feed the artifacts to the rewriter so it can
                    // replace the indys with INVOKEINTERFACE through LAMBDA_* fields.
                    Map<Integer, LambdaWrapperEmitter.Artifacts> lambdaArtifactsBySite =
                            new LinkedHashMap<>();
                    if (!result.lambdaSites().isEmpty()) {
                        org.objectweb.asm.tree.ClassNode containerCn = new org.objectweb.asm.tree.ClassNode();
                        new org.objectweb.asm.ClassReader(bytes)
                                .accept(containerCn, org.objectweb.asm.ClassReader.SKIP_FRAMES);
                        List<LambdaWrapperEmitter.Artifacts> mixinLambdas = new ArrayList<>();
                        for (LambdaSite site : result.lambdaSites()) {
                            org.objectweb.asm.tree.MethodNode synth = null;
                            for (var mn : containerCn.methods) {
                                if (mn.name.equals(site.implMethod().getName())
                                        && mn.desc.equals(site.implMethod().getDesc())) {
                                    synth = mn;
                                    break;
                                }
                            }
                            if (synth == null) {
                                warnings.add("mcdp-bridges: lambda site " + site
                                        + " references absent synthetic — skipping.");
                                continue;
                            }
                            LambdaWrapperEmitter.Artifacts art = lambdaEmitter.emit(
                                    containerCn, site, synth);
                            lambdaArtifactsBySite.put(site.siteIndex(), art);
                            mixinLambdas.add(art);
                            // Write bridge interface + impl class files.
                            Path ifaceOut = outClassesDir.resolve(art.bridgeIfaceInternal + ".class");
                            Path implOut = outClassesDir.resolve(art.bridgeImplInternal + ".class");
                            Files.createDirectories(ifaceOut.getParent());
                            Files.write(ifaceOut, art.bridgeIfaceBytes);
                            Files.write(implOut, art.bridgeImplBytes);
                        }
                        if (!mixinLambdas.isEmpty()) {
                            perMixinLambdas.put(fqn, mixinLambdas);
                        }
                    }
                    byte[] rewritten = rewriter.rewrite(bytes, result.targets(),
                            result.lambdaSites(), lambdaArtifactsBySite);
                    // Overwrite the original .class IN PLACE in the source-set output dir
                    // (instead of writing to outClassesDir as a layered second copy). The
                    // shipped jar's duplicatesStrategy=INCLUDE handles layering correctly —
                    // the codegen copy is appended last and wins — but dev runServer doesn't
                    // go through the jar. FabricLoader and ModDevGradle iterate
                    // `main.output.classesDirs` in registration order and resolve mixin
                    // classes from compileScala/compileJava/compileKotlin BEFORE they reach
                    // the codegen output dir. Sponge then applies the unrewritten mixin and
                    // bypasses the bridge entirely (mc-fluid-physics report).
                    //
                    // Alternatives considered and rejected (see ADR-0018 errata):
                    //   - Prepending the codegen dir to main.output.classesDirs would avoid
                    //     mutating any task's output, but ConfigurableFileCollection reorder
                    //     is fragile and depends on every loader honoring iteration order
                    //     (FabricLoader does; MDG behavior is unverified).
                    //   - Writing to outClassesDir then deleting the original is the same
                    //     level of mutation as overwrite — a delete is still a write to
                    //     compileScala's output dir.
                    //
                    // Mutating another task's declared output is technically a Gradle
                    // anti-pattern, but it's tolerated here because (a) the rewriter is
                    // idempotent — re-running on already-rewritten bytecode is a no-op via
                    // the LOGIC field check in MixinRewriter; (b) Java/Scala/Kotlin compile
                    // tasks track source-file state for up-to-date checks, not output
                    // state, so the post-hoc mutation doesn't trigger unnecessary recompiles;
                    // and (c) there's precedent in the mixin-tooling ecosystem — Sponge
                    // mixin's refmap remap and Loom's remapping tasks similarly mutate
                    // compile output. New classes (bridge interface, impl, lambda wrappers)
                    // still go to outClassesDir, which is properly declared.
                    Files.write(classFile, rewritten);
                    rewrittenMixins.add(fqn);
                    perMixinTargets.put(fqn, result.targets());
                    for (var e : result.targets().entrySet()) {
                        aggregated.merge(e.getKey(), new ArrayList<>(e.getValue()), (a, b) -> {
                            for (BridgeMember bm : b) if (!a.contains(bm)) a.add(bm);
                            return a;
                        });
                    }
                }
            }
        }

        if (!errors.isEmpty()) {
            for (String e : errors) log.error(e);
            throw new GradleException("mcdp-bridges: " + errors.size()
                    + " mixin(s) declared mod-private types in their class header. See log "
                    + "for details. Add the offending package(s) to mcdepprovider.sharedPackages "
                    + "or restructure the mixin.");
        }

        // Unified per-mod manifest. Single TOML file with one [[bridge]] entry per
        // (mixin, field) pair — runtime parses with MiniToml, no bespoke parser needed, and a
        // single-file findResource lookup is reliable across Fabric + NeoForge dev/prod (no
        // UnionPath directory quirks). Format documented in ADR-0019.
        if (!perMixinTargets.isEmpty() || !perMixinLambdas.isEmpty()) {
            StringBuilder sb = new StringBuilder("# auto-generated by mcdp-bridges; do not edit\n");
            for (var mixinEntry : perMixinTargets.entrySet()) {
                String mixinFqn = mixinEntry.getKey();
                for (String target : mixinEntry.getValue().keySet()) {
                    sb.append("\n[[bridge]]\n");
                    sb.append("mixin = ").append(tomlString(mixinFqn)).append('\n');
                    sb.append("field = ").append(tomlString(MixinRewriter.logicFieldName(target))).append('\n');
                    sb.append("interface = ").append(tomlString(ifaceEmitter.interfaceFqn(target))).append('\n');
                    sb.append("impl = ").append(tomlString(implEmitter.implFqn(target))).append('\n');
                }
            }
            // Lambda sites use the same [[bridge]] schema; the runtime can't (and doesn't need
            // to) tell them apart from regular bridges. ADR-0021 §Static-handler integrity check.
            for (var lambdaEntry : perMixinLambdas.entrySet()) {
                String mixinFqn = lambdaEntry.getKey();
                for (LambdaWrapperEmitter.Artifacts art : lambdaEntry.getValue()) {
                    sb.append("\n[[bridge]]\n");
                    sb.append("mixin = ").append(tomlString(mixinFqn)).append('\n');
                    sb.append("field = ").append(tomlString(art.logicFieldName)).append('\n');
                    sb.append("interface = ").append(tomlString(BridgePolicy.toDotted(art.bridgeIfaceInternal))).append('\n');
                    sb.append("impl = ").append(tomlString(BridgePolicy.toDotted(art.bridgeImplInternal))).append('\n');
                }
            }
            Files.writeString(manifestFile, sb.toString(), StandardCharsets.UTF_8);
        }

        // Emit one bridge interface + impl per aggregated target.
        for (var e : aggregated.entrySet()) {
            String target = e.getKey();
            List<BridgeMember> members = e.getValue();
            byte[] iface = ifaceEmitter.emit(target, members);
            byte[] impl = implEmitter.emit(target, members);
            String ifaceFqn = ifaceEmitter.interfaceFqn(target);
            String implFqn = implEmitter.implFqn(target);
            Path ifaceOut = outClassesDir.resolve(BridgePolicy.toInternal(ifaceFqn) + ".class");
            Path implOut = outClassesDir.resolve(BridgePolicy.toInternal(implFqn) + ".class");
            Files.createDirectories(ifaceOut.getParent());
            Files.createDirectories(implOut.getParent());
            Files.write(ifaceOut, iface);
            Files.write(implOut, impl);
        }

        for (String w : warnings) log.warn(w);
        writeReport(getReportFile().get().getAsFile().toPath(), rewrittenMixins,
                List.copyOf(aggregated.keySet()), warnings);
        log.lifecycle("mcdp-bridges: rewrote " + rewrittenMixins.size()
                + " mixin(s); emitted " + aggregated.size() + " bridge(s).");
        }
    }

    /**
     * Quote a string for TOML basic-string syntax. FQNs and field names we emit are
     * alphanumeric + {@code . _ $ /} and never contain quotes or backslashes — but escape
     * defensively to keep the writer stable against future changes.
     */
    private static String tomlString(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        out.append('"');
        return out.toString();
    }

    private static void writeReport(Path reportPath, List<String> rewritten,
                                    List<String> bridges, List<String> warnings)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# mcdp-bridges report\n\n");
        sb.append("rewritten mixins (").append(rewritten.size()).append("):\n");
        for (String s : rewritten) sb.append("  ").append(s).append('\n');
        sb.append("\nemitted bridge targets (").append(bridges.size()).append("):\n");
        for (String s : bridges) sb.append("  ").append(s).append('\n');
        sb.append("\nwarnings (").append(warnings.size()).append("):\n");
        for (String w : warnings) sb.append("  ").append(w).append('\n');
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void cleanDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        if (p.equals(dir)) return;
                        try { Files.deleteIfExists(p); } catch (IOException ignore) { }
                    });
        }
    }

}

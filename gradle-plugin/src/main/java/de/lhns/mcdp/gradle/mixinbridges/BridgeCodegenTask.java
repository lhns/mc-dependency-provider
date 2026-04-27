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
 * Walks every mixin class declared in {@code src/main/resources/*.mixins.json}, runs the scan
 * + rewrite pipeline, and writes back rewritten {@code .class} files plus generated bridge
 * interface and impl class files into a dedicated output directory. The output is layered onto
 * {@code processResources}/{@code jar} so the rewritten classes win over the original ones.
 *
 * <p>Manifest resources ({@code META-INF/mcdp-mixin-bridges/<mixinFqn>.txt}) are written into
 * {@link #getManifestOutputDir()} so they can be picked up by {@code processResources} as
 * source for the META-INF tree.</p>
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

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract org.gradle.api.file.ConfigurableFileCollection getMixinConfigFiles();

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
        Path manifestRoot = manifestDir.resolve("META-INF").resolve("mcdp-mixin-bridges");

        cleanDirectory(outClassesDir);
        cleanDirectory(manifestDir);
        Files.createDirectories(manifestRoot);

        Set<String> mixinFqns = new LinkedHashSet<>();
        for (var f : getMixinConfigFiles().getFiles()) {
            if (!f.isFile()) continue;
            mixinFqns.addAll(extractMixinFqns(f.toPath()));
        }
        if (mixinFqns.isEmpty()) {
            log.info("mcdp-mixin-bridges: no mixin classes declared in any *.mixins.json — nothing to do.");
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

        MixinRewriter rewriter = new MixinRewriter(policy, getBridgePackage().get(), frameLookup);
        BridgeInterfaceEmitter ifaceEmitter = new BridgeInterfaceEmitter(getBridgePackage().get());
        BridgeImplEmitter implEmitter = new BridgeImplEmitter(getBridgePackage().get(), frameLookup);

        // Aggregated bridges across the whole compilation: we want one bridge interface per
        // target type even if multiple mixins reference it.
        Map<String, List<BridgeMember>> aggregated = new LinkedHashMap<>();
        List<String> rewrittenMixins = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String fqn : mixinFqns) {
            String relPath = BridgePolicy.toInternal(fqn) + ".class";
            Path classFile = null;
            for (Path inDir : inDirs) {
                Path candidate = inDir.resolve(relPath);
                if (Files.isRegularFile(candidate)) { classFile = candidate; break; }
            }
            if (classFile == null) {
                warnings.add("mcdp-mixin-bridges: declared mixin class " + fqn
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
                    log.info("mcdp-mixin-bridges: " + fqn + " has no cross-classloader refs.");
                }
                case REWRITABLE -> {
                    byte[] rewritten = rewriter.rewrite(bytes, result.targets());
                    Path outClass = outClassesDir.resolve(BridgePolicy.toInternal(fqn) + ".class");
                    Files.createDirectories(outClass.getParent());
                    Files.write(outClass, rewritten);
                    rewrittenMixins.add(fqn);
                    writeManifest(manifestRoot, fqn, result.targets(), ifaceEmitter, implEmitter);
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
            throw new GradleException("mcdp-mixin-bridges: " + errors.size()
                    + " mixin(s) declared mod-private types in their class header. See log "
                    + "for details. Add the offending package(s) to mcdepprovider.sharedPackages "
                    + "or restructure the mixin.");
        }

        // Per-mod index of every manifest filename (FQN) we wrote. Runtime adapters look up
        // this index by exact path and then look up each individual manifest by exact path —
        // sidesteps NeoForge UnionPath's loose semantics for directory-style findResource.
        if (!rewrittenMixins.isEmpty()) {
            StringBuilder idx = new StringBuilder();
            for (String fqn : rewrittenMixins) idx.append(fqn).append('\n');
            Files.writeString(manifestRoot.resolveSibling("mcdp-mixin-bridges-index.txt"),
                    idx.toString(), StandardCharsets.UTF_8);
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
            Files.write(ifaceOut, iface);
            Files.write(implOut, impl);
        }

        for (String w : warnings) log.warn(w);
        writeReport(getReportFile().get().getAsFile().toPath(), rewrittenMixins,
                List.copyOf(aggregated.keySet()), warnings);
        log.lifecycle("mcdp-mixin-bridges: rewrote " + rewrittenMixins.size()
                + " mixin(s); emitted " + aggregated.size() + " bridge(s).");
        }
    }

    private static void writeManifest(Path manifestRoot, String mixinFqn,
                                      Map<String, List<BridgeMember>> targets,
                                      BridgeInterfaceEmitter ifaceEmitter,
                                      BridgeImplEmitter implEmitter) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String target : targets.keySet()) {
            sb.append("bridge=").append(ifaceEmitter.interfaceFqn(target)).append('\n');
            sb.append("impl=").append(implEmitter.implFqn(target)).append('\n');
            sb.append("field=").append(MixinRewriter.logicFieldName(target)).append('\n');
        }
        Files.writeString(manifestRoot.resolve(mixinFqn + ".txt"), sb.toString(),
                StandardCharsets.UTF_8);
    }

    private static void writeReport(Path reportPath, List<String> rewritten,
                                    List<String> bridges, List<String> warnings)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# mcdp-mixin-bridges report\n\n");
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

    /**
     * Parse a Sponge-Mixin {@code *.mixins.json} config and return the FQNs of every declared
     * mixin class. The schema relevant here:
     * <pre>
     * { "package": "com.example.mixin", "mixins": [...], "client": [...], "server": [...] }
     * </pre>
     */
    static List<String> extractMixinFqns(Path mixinJson) throws IOException {
        String json = Files.readString(mixinJson, StandardCharsets.UTF_8);
        Object root;
        try {
            root = MixinJson.parse(json);
        } catch (RuntimeException e) {
            throw new IOException("mcdp-mixin-bridges: failed to parse " + mixinJson, e);
        }
        if (!(root instanceof Map<?, ?> obj)) {
            throw new IOException("mcdp-mixin-bridges: " + mixinJson + " is not a JSON object.");
        }
        Object pkgObj = obj.get("package");
        String pkg = pkgObj instanceof String ? ((String) pkgObj) : "";
        List<String> out = new ArrayList<>();
        for (String key : List.of("mixins", "client", "server")) {
            Object arr = obj.get(key);
            if (!(arr instanceof List<?> list)) continue;
            for (Object o : list) {
                if (o instanceof String s) {
                    out.add(pkg.isEmpty() ? s : pkg + "." + s);
                }
            }
        }
        return out;
    }
}

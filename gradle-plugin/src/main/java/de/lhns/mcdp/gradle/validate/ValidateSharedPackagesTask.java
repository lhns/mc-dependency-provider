package de.lhns.mcdp.gradle.validate;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Wires both ADR-0024 validators (over-share and under-share) into the build. Wired into
 * {@code :check} so it runs as part of standard verification; emits all diagnostics and
 * fails the build if any are non-empty.
 */
public abstract class ValidateSharedPackagesTask extends DefaultTask {

    /**
     * All compiled class output directories (compileJava, compileScala, compileKotlin). Same
     * shape as {@code BridgeCodegenTask.getCompiledClassesDirs()}.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getCompiledClassesDirs();

    /**
     * Resolved {@code sharedPackages} list at task-execution time.
     */
    @Input
    public abstract ListProperty<String> getSharedPackages();

    /**
     * Class-level annotation FQNs marking cross-loader-injected types. See
     * {@code BridgeCodegenExtension.getCrossLoaderAnnotations}.
     */
    @Input
    public abstract ListProperty<String> getCrossLoaderAnnotations();

    @TaskAction
    public void run() throws IOException {
        List<String> shared = getSharedPackages().get();
        List<String> crossLoaderAnnos = getCrossLoaderAnnotations().get();

        // Collect every .class on the inputs.
        List<byte[]> classes = new ArrayList<>();
        for (var dir : getCompiledClassesDirs()) {
            if (!dir.isDirectory()) continue;
            try (Stream<Path> walk = Files.walk(dir.toPath())) {
                for (var p : walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).toList()) {
                    classes.add(Files.readAllBytes(p));
                }
            }
        }
        if (classes.isEmpty()) return;

        List<Diagnostic> diagnostics = new ArrayList<>();

        // Validator A: over-share.
        SharedPackageContentValidator overShare = new SharedPackageContentValidator(shared);
        for (byte[] b : classes) {
            diagnostics.addAll(overShare.validate(b));
        }

        // Validator B: under-share.
        CrossLoaderCastValidator underShare = new CrossLoaderCastValidator(crossLoaderAnnos, shared);
        diagnostics.addAll(underShare.validate(classes));

        if (diagnostics.isEmpty()) return;

        StringBuilder report = new StringBuilder();
        report.append("validateSharedPackages found ").append(diagnostics.size())
                .append(" issue(s):\n\n");
        for (Diagnostic d : diagnostics) {
            report.append(d.message()).append("\n\n");
        }
        getLogger().error(report.toString());
        throw new GradleException("validateSharedPackages: " + diagnostics.size()
                + " issue(s) — see error log above (ADR-0024).");
    }
}

package de.lhns.mcdp.gradle;

import de.lhns.mcdp.deps.Manifest;
import de.lhns.mcdp.deps.ManifestIo;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies ADR-0007 dev-mode parity: at execution time, strips from a run task's classpath every
 * jar whose SHA-256 matches an entry in the project's generated manifest. The provider's
 * runtime pipeline then becomes the only path by which those deps reach the mod, matching
 * production behavior exactly.
 * <p>
 * We intentionally do not depend on ModDevGradle or Loom APIs. The hook is:
 * <ol>
 *   <li>Opt-in via {@code mcdepprovider.patchRunTasks} (default: {@code runClient},
 *       {@code runServer}, {@code runGameTestServer}, {@code runData}).</li>
 *   <li>For each matching {@link JavaExec} task, a {@code doFirst} action reads the generated
 *       manifest, hashes every file on the current classpath, and installs a filtered replacement.</li>
 * </ol>
 * Filtering by SHA is robust because the manifest's SHAs are computed from the same Gradle-cached
 * jars that end up on the run-task classpath. If a jar isn't listed in the manifest (typically
 * because it's a platform-provided dep like Minecraft or NeoForge), it passes through untouched.
 */
final class RunTaskClasspathPatch {

    private RunTaskClasspathPatch() {}

    static void apply(Project project,
                      TaskProvider<GenerateMcdpManifestTask> generate,
                      ListProperty<String> runTaskNames) {
        project.getTasks().withType(JavaExec.class).configureEach(exec -> {
            // Resolve the list lazily per-task, AFTER the user's mcdepprovider { } block
            // has run — critical because Loom / Scala plugins apply java plugin
            // synchronously during their own apply(), so withPlugin("java") would fire
            // before the user's DSL executes. getOrElse here runs at task-configure time.
            Set<String> targets = Set.copyOf(runTaskNames.getOrElse(List.of()));
            if (targets.isEmpty() || !targets.contains(exec.getName())) return;

            // Make sure the manifest exists before the patcher runs — the run task consumes it.
            exec.dependsOn(generate);

            exec.doFirst("mcdpProviderPatchClasspath", t -> {
                Path manifestFile = generate.get().getOutputFile().get().getAsFile().toPath();
                Set<String> manifestShas;
                try {
                    Manifest manifest = ManifestIo.read(manifestFile);
                    manifestShas = manifest.libraries().stream()
                            .map(Manifest.Library::sha256)
                            .collect(Collectors.toUnmodifiableSet());
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "mcdepprovider: failed to read manifest for run-task patching at "
                                    + manifestFile, e);
                }

                FileCollection original = exec.getClasspath();
                FileCollection filtered = original.filter(f -> {
                    if (!f.isFile() || !f.getName().endsWith(".jar")) return true;
                    String sha;
                    try {
                        sha = sha256(f.toPath());
                    } catch (IOException ioe) {
                        // Can't hash → keep on classpath, safer than removing.
                        project.getLogger().warn(
                                "mcdepprovider: failed to hash {} for classpath filtering; keeping on run-task classpath",
                                f, ioe);
                        return true;
                    }
                    return !manifestShas.contains(sha);
                });

                int removed = original.getFiles().size() - filtered.getFiles().size();
                project.getLogger().lifecycle(
                        "mcdepprovider: stripped {} manifest-listed jars from {} classpath (ADR-0007 dev-mode parity)",
                        removed, exec.getName());

                exec.setClasspath(filtered);
            });
        });
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available on every JVM", e);
        }
        md.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(md.digest());
    }
}

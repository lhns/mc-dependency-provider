package io.github.mclibprovider.gradle;

import io.github.mclibprovider.deps.Manifest;
import io.github.mclibprovider.deps.ManifestIo;
import io.github.mclibprovider.deps.Sha256;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Walks the project's resolved {@code runtimeClasspath}, filters out platform-provided deps
 * (Minecraft, NeoForge, Fabric, the provider itself — configured via
 * {@link McLibProviderExtension#getExclusions()}), and emits
 * {@code META-INF/mc-jvm-mod.toml}.
 * <p>
 * URL reconstruction: tries each Maven repository declared on the project in order and picks the
 * first one whose URL pattern is a plausible source. We never re-download — Gradle already has
 * the file locally; we just need the canonical URL to record in the manifest.
 */
public abstract class GenerateMcLibManifestTask extends DefaultTask {

    @Input
    public abstract Property<String> getLang();

    @Input
    public abstract ListProperty<String> getSharedPackages();

    @Input
    public abstract ListProperty<String> getExclusions();

    @Input
    public abstract ListProperty<String> getRepositoryUrls();

    @InputFiles
    public abstract org.gradle.api.file.ConfigurableFileCollection getResolvedArtifactFiles();

    /** Populated by the plugin; full metadata we can't get from InputFiles alone. */
    @Input
    public abstract ListProperty<ArtifactCoord> getArtifactCoords();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() throws IOException {
        List<Pattern> excludePatterns = new ArrayList<>();
        for (String ex : getExclusions().get()) excludePatterns.add(toPattern(ex));

        List<String> repoUrls = getRepositoryUrls().get();
        List<ArtifactCoord> coords = getArtifactCoords().get();

        List<Manifest.Library> libs = new ArrayList<>();
        for (ArtifactCoord c : coords) {
            if (matchesAny(excludePatterns, c.group(), c.name())) continue;

            byte[] bytes = Files.readAllBytes(Path.of(c.filePath()));
            String sha = Sha256.hex(bytes);
            String url = urlFor(c, repoUrls);

            String displayCoords = c.group() + ":" + c.name() + ":" + c.version()
                    + (c.classifier() == null || c.classifier().isEmpty() ? "" : ":" + c.classifier());
            libs.add(new Manifest.Library(displayCoords, url, sha));
        }

        Manifest manifest = new Manifest(
                getLang().get(),
                getSharedPackages().get(),
                libs);

        Path out = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(out.getParent());
        ManifestIo.write(manifest, out);
    }

    private static String urlFor(ArtifactCoord c, List<String> repoUrls) {
        String groupPath = c.group().replace('.', '/');
        String filename = c.name() + "-" + c.version()
                + (c.classifier() == null || c.classifier().isEmpty() ? "" : "-" + c.classifier())
                + "." + (c.extension() == null || c.extension().isEmpty() ? "jar" : c.extension());
        String relative = groupPath + "/" + c.name() + "/" + c.version() + "/" + filename;

        // Try each declared remote; pick the first that makes sense. For now we emit the first
        // one — HEAD-probe validation can be added later if needed.
        for (String base : repoUrls) {
            if (!base.startsWith("http://") && !base.startsWith("https://")) continue;
            String b = base.endsWith("/") ? base : base + "/";
            return b + relative;
        }
        return "https://repo1.maven.org/maven2/" + relative;
    }

    private static boolean matchesAny(List<Pattern> patterns, String group, String name) {
        String ga = group + ":" + name;
        for (Pattern p : patterns) {
            if (p.matcher(ga).matches()) return true;
        }
        return false;
    }

    private static Pattern toPattern(String exclusion) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < exclusion.length(); i++) {
            char c = exclusion.charAt(i);
            if (c == '*') sb.append(".*");
            else if (c == '.') sb.append("\\.");
            else if (Character.isLetterOrDigit(c) || c == ':' || c == '-' || c == '_') sb.append(c);
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }

    /**
     * Captured form of a resolved artifact — carried as a Gradle-serializable input so task
     * up-to-date checks work. Built by the plugin at configure time.
     */
    public record ArtifactCoord(
            String group,
            String name,
            String version,
            String classifier,
            String extension,
            String filePath) implements java.io.Serializable {

        public static ArtifactCoord from(ResolvedArtifact a) {
            ModuleVersionIdentifier id = a.getModuleVersion().getId();
            return new ArtifactCoord(
                    id.getGroup(),
                    id.getName(),
                    id.getVersion(),
                    a.getClassifier() == null ? "" : a.getClassifier(),
                    a.getExtension() == null ? "jar" : a.getExtension(),
                    a.getFile().getAbsolutePath());
        }
    }

    public static List<String> collectRepositoryUrls(Iterable<ArtifactRepository> repos) {
        List<String> urls = new ArrayList<>();
        for (ArtifactRepository r : repos) {
            if (r instanceof MavenArtifactRepository m) {
                urls.add(m.getUrl().toString());
            }
        }
        return urls;
    }

    public static List<ArtifactCoord> collectArtifactCoords(Configuration config) {
        List<ArtifactCoord> list = new ArrayList<>();
        for (ResolvedArtifact a : config.getResolvedConfiguration().getResolvedArtifacts()) {
            list.add(ArtifactCoord.from(a));
        }
        return list;
    }
}

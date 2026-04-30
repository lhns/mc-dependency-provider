package de.lhns.mcdp.gradle;

import de.lhns.mcdp.deps.Manifest;
import de.lhns.mcdp.deps.ManifestIo;
import de.lhns.mcdp.deps.Sha256;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks the project's resolved {@code mcdepImplementation} closure, subtracts any artifact whose
 * {@code group:name} is also resolved through the rest of the project's classpath as a non-mclib
 * dep (i.e. genuinely platform-provided), and emits {@code META-INF/mcdepprovider.toml}.
 * <p>
 * URL reconstruction: tries each Maven repository declared on the project in order and picks the
 * first one whose URL pattern is a plausible source. We never re-download — Gradle already has
 * the file locally; we just need the canonical URL to record in the manifest.
 */
public abstract class GenerateMcdpManifestTask extends DefaultTask {

    @Input
    public abstract Property<String> getLang();

    @Input
    public abstract ListProperty<String> getSharedPackages();

    /**
     * {@code group:name} keys for artifacts that the platform already provides. Anything in the
     * mcdepImplementation closure with a key in this set is dropped from the manifest.
     */
    @Input
    public abstract ListProperty<String> getPlatformProvidedKeys();

    @Input
    public abstract ListProperty<String> getRepositoryUrls();

    @InputFiles
    public abstract org.gradle.api.file.ConfigurableFileCollection getResolvedArtifactFiles();

    /** Populated by the plugin; full metadata we can't get from InputFiles alone. */
    @Input
    public abstract ListProperty<ArtifactCoord> getArtifactCoords();

    /**
     * Absolute paths to the source-set output dirs that hold the mod's compiled classes plus
     * the bridge codegen output. Captured at build time so the runtime adapter doesn't have to
     * walk the filesystem to find {@code build/}. Empty for production-jar builds (the
     * {@code processResources} flow strips the field there); populated by the plugin from the
     * project's {@code main} source set in dev builds.
     */
    @Input
    public abstract ListProperty<String> getDevRoots();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() throws IOException {
        Set<String> platformKeys = new HashSet<>(getPlatformProvidedKeys().get());

        List<String> repoUrls = getRepositoryUrls().get();
        List<ArtifactCoord> coords = getArtifactCoords().get();

        UrlResolver urlResolver = new UrlResolver(repoUrls);

        List<Manifest.Library> libs = new ArrayList<>();
        for (ArtifactCoord c : coords) {
            if (platformKeys.contains(c.group() + ":" + c.name())) continue;

            byte[] bytes = Files.readAllBytes(Path.of(c.filePath()));
            String sha = Sha256.hex(bytes);
            String url = urlResolver.resolve(c, getLogger()::info);

            String displayCoords = c.group() + ":" + c.name() + ":" + c.version()
                    + (c.classifier() == null || c.classifier().isEmpty() ? "" : ":" + c.classifier());
            libs.add(new Manifest.Library(displayCoords, url, sha));
        }

        Manifest manifest = new Manifest(
                getLang().get(),
                getSharedPackages().get(),
                libs,
                getDevRoots().getOrElse(List.of()));

        Path out = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(out.getParent());
        ManifestIo.write(manifest, out);
    }

    /**
     * Finds the canonical download URL for each artifact. With a single declared repo we emit
     * against that repo directly (fast path). With multiple repos, HEAD-probes each one in order
     * and picks the first to return 200, caching the repo-of-record per {@code group:name:version}
     * so we do not re-probe the same coordinate twice. Falls back to the first declared repo (or
     * Maven Central if none are HTTP) when all probes fail — keeps manifest generation non-fatal
     * while still pinning a stable URL.
     */
    static final class UrlResolver {
        private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

        private final List<String> httpRepos;
        private final Map<String, String> coordRepoCache = new HashMap<>();
        private HttpClient http; // lazy — avoid instantiation for single-repo projects

        UrlResolver(List<String> repoUrls) {
            List<String> cleaned = new ArrayList<>();
            for (String u : repoUrls) {
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    cleaned.add(u.endsWith("/") ? u : u + "/");
                }
            }
            this.httpRepos = cleaned;
        }

        String resolve(ArtifactCoord c, java.util.function.Consumer<String> log) {
            String relative = relativePathFor(c);

            if (httpRepos.isEmpty()) {
                return "https://repo1.maven.org/maven2/" + relative;
            }
            if (httpRepos.size() == 1) {
                return httpRepos.get(0) + relative;
            }

            String coordKey = c.group() + ":" + c.name() + ":" + c.version();
            String cached = coordRepoCache.get(coordKey);
            if (cached != null) return cached + relative;

            for (String repo : httpRepos) {
                String candidate = repo + relative;
                if (headOk(candidate)) {
                    coordRepoCache.put(coordKey, repo);
                    return candidate;
                }
            }
            // None responded with 200 — emit against the first repo anyway. Mod authors can fix
            // up their repos list if the resulting URL 404s at runtime.
            String fallback = httpRepos.get(0);
            log.accept("mcdepprovider: HEAD probe failed for " + coordKey
                    + " across " + httpRepos.size() + " repos; emitting against " + fallback);
            coordRepoCache.put(coordKey, fallback);
            return fallback + relative;
        }

        private boolean headOk(String url) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .timeout(PROBE_TIMEOUT)
                        .build();
                HttpResponse<Void> res = httpClient().send(req, HttpResponse.BodyHandlers.discarding());
                return res.statusCode() / 100 == 2;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return false;
            }
        }

        private HttpClient httpClient() {
            if (http == null) {
                http = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(PROBE_TIMEOUT)
                        .build();
            }
            return http;
        }

        private static String relativePathFor(ArtifactCoord c) {
            String groupPath = c.group().replace('.', '/');
            String filename = c.name() + "-" + c.version()
                    + (c.classifier() == null || c.classifier().isEmpty() ? "" : "-" + c.classifier())
                    + "." + (c.extension() == null || c.extension().isEmpty() ? "jar" : c.extension());
            return groupPath + "/" + c.name() + "/" + c.version() + "/" + filename;
        }
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

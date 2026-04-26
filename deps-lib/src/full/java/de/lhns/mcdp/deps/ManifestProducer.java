package de.lhns.mcdp.deps;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Build-time resolver: takes a list of Maven coordinates + repositories, returns a flat {@link Manifest}
 * with transitive closure, URLs, and SHA-256s. Used by the Gradle plugin (and future sbt/Maven/CLI
 * siblings) to emit {@code META-INF/mcdepprovider.toml} at build time.
 * <p>
 * Not on the runtime classpath. See ADR-0004.
 */
public final class ManifestProducer {

    private final RepositorySystem system;
    private final Path localRepoDir;

    public ManifestProducer(Path localRepoDir) {
        this.localRepoDir = localRepoDir;
        this.system = new RepositorySystemSupplier().get();
    }

    /** A remote Maven repository to resolve against. */
    public record Remote(String id, String url) {
        public RemoteRepository toAether() {
            return new RemoteRepository.Builder(id, "default", url).build();
        }
    }

    public static final Remote MAVEN_CENTRAL = new Remote("central", "https://repo1.maven.org/maven2/");

    /** Exclusion by {@code groupId:artifactId} — e.g. {@code "net.neoforged:neoforge"}. */
    public record Exclusion(String groupId, String artifactId) {
        static Exclusion parse(String ga) {
            String[] parts = ga.split(":");
            if (parts.length != 2) throw new IllegalArgumentException("expected groupId:artifactId, got: " + ga);
            return new Exclusion(parts[0], parts[1]);
        }
        boolean matches(Artifact a) {
            return a.getGroupId().equals(groupId) && a.getArtifactId().equals(artifactId);
        }
    }

    /** Resolve the transitive closure and produce a manifest. */
    public Manifest produce(
            String lang,
            List<String> sharedPackages,
            List<String> coords,
            List<Remote> repos,
            List<String> exclusions) throws IOException {

        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(localRepoDir.toFile())));

        List<RemoteRepository> remotes = new ArrayList<>();
        for (Remote r : repos) remotes.add(r.toAether());

        List<Exclusion> parsedExclusions = new ArrayList<>();
        for (String e : exclusions) parsedExclusions.add(Exclusion.parse(e));

        Set<String> seen = new HashSet<>();
        List<Manifest.Library> libs = new ArrayList<>();

        for (String coord : coords) {
            Artifact root = new DefaultArtifact(coord);
            CollectRequest collect = new CollectRequest();
            collect.setRoot(new Dependency(root, "runtime"));
            collect.setRepositories(remotes);

            DependencyRequest req = new DependencyRequest(collect, null);
            DependencyResult result;
            try {
                result = system.resolveDependencies(session, req);
            } catch (DependencyResolutionException e) {
                throw new IOException("Failed to resolve " + coord, e);
            }

            for (ArtifactResult ar : result.getArtifactResults()) {
                Artifact a = ar.getArtifact();
                if (parsedExclusions.stream().anyMatch(ex -> ex.matches(a))) continue;

                String gavKey = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion()
                        + ":" + a.getClassifier() + ":" + a.getExtension();
                if (!seen.add(gavKey)) continue;

                Path file = a.getFile().toPath();
                byte[] bytes = Files.readAllBytes(file);
                String sha = Sha256.hex(bytes);

                String url = urlFor(a, pickRemoteFor(ar, remotes));
                String displayCoords = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion()
                        + (a.getClassifier() == null || a.getClassifier().isEmpty() ? "" : ":" + a.getClassifier());
                libs.add(new Manifest.Library(displayCoords, url, sha));
            }
        }

        return new Manifest(lang, sharedPackages, libs);
    }

    private static RemoteRepository pickRemoteFor(ArtifactResult ar, List<RemoteRepository> remotes) {
        if (ar.getRepository() instanceof RemoteRepository remote) return remote;
        // Fell through local cache; default to the first remote in the list.
        return remotes.isEmpty() ? MAVEN_CENTRAL.toAether() : remotes.get(0);
    }

    private static String urlFor(Artifact a, RemoteRepository remote) {
        String base = remote.getUrl();
        if (!base.endsWith("/")) base += "/";
        String groupPath = a.getGroupId().replace('.', '/');
        String filename = a.getArtifactId() + "-" + a.getVersion()
                + (a.getClassifier() == null || a.getClassifier().isEmpty() ? "" : "-" + a.getClassifier())
                + "." + a.getExtension();
        return base + groupPath + "/" + a.getArtifactId() + "/" + a.getVersion() + "/" + filename;
    }

    public static Manifest produceSimple(
            Path localRepoDir,
            String lang,
            List<String> coords,
            String... exclusions) throws IOException {
        ManifestProducer producer = new ManifestProducer(localRepoDir);
        return producer.produce(lang, List.of(), coords, List.of(MAVEN_CENTRAL), Arrays.asList(exclusions));
    }
}

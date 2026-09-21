package de.lhns.mcdp.gradle.testfixtures;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

/**
 * Gives the plugin's parity tests the <em>real</em> {@code de.lhns.mcdp.core.ModClassLoader} to
 * compare against, without putting {@code :core} on the plugin's classpath: it locates
 * {@code ModClassLoader.java} in the checkout, compiles that one file in-process with
 * {@code javax.tools}, and loads the result in an isolated classloader.
 *
 * <p>Why this rather than the obvious alternatives:
 * <ul>
 *   <li><b>Not a source parse.</b> Parsing recovers the prefix literals but cannot run
 *       {@code normalizePrefixes}, so the behavioural half of the parity check would stay a
 *       hand-written transcription — the exact defect these tests exist to remove.</li>
 *   <li><b>Not {@code core/build/classes} via ASM or reflection.</b> {@code :gradle-plugin:test}
 *       has no task dependency on {@code :core:compileJava} and must not grow one, so that
 *       directory is absent on a clean checkout and stale whenever core was edited but not
 *       rebuilt. The source file is always present and always current.</li>
 *   <li><b>Not a generated shared resource.</b> That needs a build-file change and still only
 *       covers data, not the normalization rule.</li>
 * </ul>
 *
 * <p>{@code ModClassLoader.java} references nothing outside the JDK, which is what makes the
 * single-file compile viable; if that ever stops being true the compile fails with the
 * diagnostics, which is the correct signal.
 *
 * <p>Every failure mode here throws. Nothing is skipped and nothing degrades to a vacuous pass:
 * a parity test that quietly no-ops when it cannot find {@code :core} would be worse than no
 * test at all.
 */
public final class RuntimeLoaderSource {

    private static final String FQN = "de.lhns.mcdp.core.ModClassLoader";
    private static final String REL_PATH = "core/src/main/java/de/lhns/mcdp/core/ModClassLoader.java";

    private RuntimeLoaderSource() {
    }

    /** Compiled once per JVM; both parity tests share it. */
    private static final class Holder {
        static final Class<?> MOD_CLASS_LOADER = compileAndLoad();
    }

    /** The runtime's canonical {@code PLATFORM_PREFIXES} (package-private static field). */
    @SuppressWarnings("unchecked")
    public static List<String> platformPrefixes() {
        try {
            Field f = Holder.MOD_CLASS_LOADER.getDeclaredField("PLATFORM_PREFIXES");
            f.setAccessible(true);
            List<String> prefixes = (List<String>) f.get(null);
            // An empty list would let the "contains every prefix" assertion pass vacuously.
            if (prefixes == null || prefixes.isEmpty()) {
                throw new IllegalStateException(FQN + ".PLATFORM_PREFIXES is empty - refusing to "
                        + "compare against nothing");
            }
            return prefixes;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + FQN + ".PLATFORM_PREFIXES", e);
        }
    }

    /**
     * Runs the runtime's own shared-package normalization by constructing a real
     * {@code ModClassLoader} and reading back {@code sharedPackages()} — the public path the
     * private {@code normalizePrefixes} sits behind. No URLs, so nothing is ever loaded through it.
     */
    @SuppressWarnings("unchecked")
    public static List<String> normalizePrefixes(List<String> raw) {
        try {
            Constructor<?> ctor = Holder.MOD_CLASS_LOADER.getConstructor(
                    String.class, URL[].class, ClassLoader.class, List.class);
            Object loader = ctor.newInstance("parity-probe", new URL[0],
                    Holder.MOD_CLASS_LOADER.getClassLoader(), raw);
            try (AutoCloseable closeable = (AutoCloseable) loader) {
                return (List<String>) Holder.MOD_CLASS_LOADER.getMethod("sharedPackages").invoke(loader);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot run " + FQN + "'s shared-package normalization", e);
        }
    }

    private static Class<?> compileAndLoad() {
        Path source = locateSource();
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException("no system Java compiler - these parity tests need a "
                    + "JDK test runtime to compile " + REL_PATH + "; they must not be skipped");
        }
        try {
            Path out = Files.createTempDirectory("mcdp-core-parity");
            deleteOnExit(out);
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, null, null)) {
                // Empty classpath: the compile must stay self-contained, and nothing on the
                // plugin's test classpath has any business satisfying a core reference.
                boolean ok = javac.getTask(null, files, diagnostics,
                                List.of("-d", out.toString(), "-classpath", "", "-proc:none", "-nowarn"),
                                null, files.getJavaFileObjects(source.toFile()))
                        .call();
                if (!ok) {
                    throw new IllegalStateException("failed to compile " + source + " standalone:\n"
                            + diagnostics.getDiagnostics().stream()
                            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                            .map(Object::toString)
                            .reduce("", (a, b) -> a + b + "\n"));
                }
            }
            URLClassLoader loader = new URLClassLoader(new URL[]{out.toUri().toURL()},
                    RuntimeLoaderSource.class.getClassLoader());
            return Class.forName(FQN, true, loader);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot compile " + source, e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(FQN + " compiled but did not load", e);
        }
    }

    /**
     * Walks up from the working directory, so the tests work whether Gradle runs them with
     * {@code gradle-plugin/} as the working directory or an IDE runs them from the repo root.
     */
    private static Path locateSource() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(REL_PATH);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot find " + REL_PATH + " by walking up from " + start
                + " - the parity tests compare against core's real source and will not pass "
                + "without it");
    }

    private static void deleteOnExit(Path dir) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // best-effort cleanup of a temp dir; never worth failing a test run over
            }
        }));
    }
}

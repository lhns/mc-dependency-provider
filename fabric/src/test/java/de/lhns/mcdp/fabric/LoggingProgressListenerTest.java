package de.lhns.mcdp.fabric;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoggingProgressListener} against a recording slf4j {@link Logger} proxy: which level each
 * event lands at, and what it says.
 */
class LoggingProgressListenerTest {

    /** One logging call: the level (method name), the format, and its arguments flattened. */
    record Line(String level, String format, List<Object> args) {}

    private static final Set<String> LEVELS = Set.of("trace", "debug", "info", "warn", "error");

    /**
     * A de_DE default locale formats the decimal as a comma, so the log read "1,5 KB". Pinned
     * to the dot, whatever the machine's locale.
     */
    @Test
    void humanBytesIgnoresTheDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1.5 KB", LoggingProgressListener.humanBytes(1536));
            assertEquals("1.5 MB", LoggingProgressListener.humanBytes(1536L * 1024L));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void humanBytesUnitBoundaries() {
        assertEquals("0 B", LoggingProgressListener.humanBytes(0));
        assertEquals("1023 B", LoggingProgressListener.humanBytes(1023));
        assertEquals("1.0 KB", LoggingProgressListener.humanBytes(1024));
        assertEquals("1.0 MB", LoggingProgressListener.humanBytes(1024L * 1024L));
    }

    /** Most mods have no libraries: no log line and no stderr banner for them. */
    @Test
    void startedWithNoLibrariesSaysNothing() {
        List<Line> lines = new ArrayList<>();
        String stderr = captureStderr(() ->
                new LoggingProgressListener(recorder(lines), "lpl-empty-mod").started(0, 0L));

        assertEquals(List.of(), lines);
        assertEquals("", stderr);
    }

    @Test
    void startedLogsTheCountAndPrintsOneBanner() {
        List<Line> lines = new ArrayList<>();
        String stderr = captureStderr(() -> {
            new LoggingProgressListener(recorder(lines), "lpl-one-mod").started(1, 0L);
            new LoggingProgressListener(recorder(lines), "lpl-two-mod").started(2, 0L);
        });

        assertEquals(List.of(
                new Line("info", "mcdp[{}]: resolving {} {}", List.of("lpl-one-mod", 1, "library")),
                new Line("info", "mcdp[{}]: resolving {} {}", List.of("lpl-two-mod", 2, "libraries"))),
                lines);
        assertTrue(stderr.contains("resolving 1 library for lpl-one-mod"), stderr);
        assertTrue(stderr.contains("resolving 2 libraries for lpl-two-mod"), stderr);
    }

    /** Per-library start lines are DEBUG, so a many-library mod does not flood latest.log. */
    @Test
    void libraryStartedIsDebug() {
        List<Line> lines = new ArrayList<>();
        new LoggingProgressListener(recorder(lines), "lpl-debug-mod")
                .libraryStarted(1, 3, "com.example:lib:1.0", 0L);

        assertEquals(List.of(new Line("debug", "mcdp[{}]: ({}/{}) {} ...",
                List.of("lpl-debug-mod", 1, 3, "com.example:lib:1.0"))), lines);
    }

    @Test
    void libraryFinishedSaysCachedOrTheDownloadedSize() {
        List<Line> lines = new ArrayList<>();
        LoggingProgressListener listener = new LoggingProgressListener(recorder(lines), "lpl-done-mod");
        listener.libraryFinished(1, 2, "com.example:a:1.0", 1536, true);
        listener.libraryFinished(2, 2, "com.example:b:1.0", 1536, false);

        assertEquals(List.of(
                new Line("info", "mcdp[{}]: ({}/{}) {} {}",
                        List.of("lpl-done-mod", 1, 2, "com.example:a:1.0", "(cached)")),
                new Line("info", "mcdp[{}]: ({}/{}) {} {}",
                        List.of("lpl-done-mod", 2, 2, "com.example:b:1.0", "1.5 KB"))),
                lines);
    }

    @Test
    void finishedLogsTheElapsedTimeAtInfo() {
        List<Line> lines = new ArrayList<>();
        LoggingProgressListener listener = new LoggingProgressListener(recorder(lines), "lpl-finish-mod");
        captureStderr(() -> listener.started(1, 0L));
        lines.clear();
        listener.finished();

        assertEquals(1, lines.size(), lines.toString());
        Line line = lines.get(0);
        assertEquals("info", line.level());
        assertEquals("mcdp[{}]: resolved in {} ms", line.format());
        assertEquals("lpl-finish-mod", line.args().get(0));
        long elapsedMs = (Long) line.args().get(1);
        assertTrue(elapsedMs >= 0 && elapsedMs < 60_000, "elapsed " + elapsedMs);
    }

    // --- helpers ---

    /**
     * Records every {@code trace/debug/info/warn/error(String, ...)} call; varargs are flattened
     * so {@code info(fmt, a, b)} and {@code info(fmt, new Object[]{a, b})} record the same line.
     * Every level reports enabled.
     */
    private static Logger recorder(List<Line> lines) {
        InvocationHandler handler = (self, method, args) -> {
            String name = method.getName();
            if (LEVELS.contains(name) && args != null && args.length > 0 && args[0] instanceof String fmt) {
                List<Object> rest = new ArrayList<>();
                for (int i = 1; i < args.length; i++) {
                    if (args[i] instanceof Object[] varargs) rest.addAll(Arrays.asList(varargs));
                    else rest.add(args[i]);
                }
                lines.add(new Line(name, fmt, rest));
                return null;
            }
            if (name.startsWith("is") && name.endsWith("Enabled")) return true;
            if (name.equals("getName")) return "recorder";
            if (method.isDefault()) return InvocationHandler.invokeDefault(self, method, args);
            switch (name) {
                case "toString": return "RecordingLogger";
                case "hashCode": return System.identityHashCode(self);
                case "equals": return self == args[0];
                default: throw new UnsupportedOperationException("Logger." + name + " is not recorded");
            }
        };
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[]{Logger.class}, handler);
    }

    private static String captureStderr(Runnable body) {
        PrintStream saved = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(saved);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}

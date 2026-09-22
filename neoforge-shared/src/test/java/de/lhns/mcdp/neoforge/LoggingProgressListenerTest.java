package de.lhns.mcdp.neoforge;

import net.neoforged.fml.loading.progress.StartupNotificationManager;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs in all three NeoForge trees, each against its own loader jar (3.0.45, 4.0.42, 10.0.36) —
 * the jar the band really runs on, since the listener finds FML's loading-screen rail by class
 * name and a wrong name compiles fine everywhere.
 */
class LoggingProgressListenerTest {

    /**
     * The listener used to look up {@code net.neoforged.fml.StartupNotificationManager}, which
     * no loader has, so nothing ever reached the loading screen. Asserted on what
     * {@code StartupNotificationManager} actually holds afterwards, not on the lookup.
     */
    @Test
    void startedPushesAMessageIntoFmlsStartupNotificationManager() {
        String modId = "snm_probe_" + Long.toHexString(System.nanoTime());
        LoggingProgressListener listener =
                new LoggingProgressListener(LoggerFactory.getLogger(getClass()), modId);

        listener.started(3, 0L);

        List<String> texts = StartupNotificationManager.getMessages().stream()
                .map(m -> m.message().getText())
                .toList();
        assertTrue(texts.contains("mcdp: resolving deps for " + modId),
                "expected the resolve message on FML's notification rail, got " + texts);
    }

    /**
     * The client nightly greps the NeoForge cells' logs for this line, as its proof that a real
     * boot put a message where the early-display renderer reads it. So it must appear exactly
     * once per mod, after the first post, carrying the size {@code getMessages()} really reports.
     */
    @Test
    void firstPostedMessageLogsTheRailSizeOnce() {
        String modId = "rail_probe_" + Long.toHexString(System.nanoTime());
        List<String> infos = new ArrayList<>();
        LoggingProgressListener listener = new LoggingProgressListener(capturingInfo(infos), modId);

        listener.started(2, 0L);
        int railAfterFirstPost = StartupNotificationManager.getMessages().size();
        listener.libraryStarted(1, 2, "org.example:a:1", 0L);
        listener.libraryStarted(2, 2, "org.example:b:1", 0L);

        List<String> posted = infos.stream()
                .filter(l -> l.contains("loading-screen message posted"))
                .toList();
        assertEquals(List.of("mcdp[" + modId + "]: loading-screen message posted ("
                        + railAfterFirstPost + " on the rail)"), posted,
                "expected exactly one rail line; all INFO lines: " + infos);
        assertTrue(railAfterFirstPost >= 1, "the rail was empty right after a post");
    }

    /** An SLF4J {@link Logger} that records each formatted INFO line and ignores the rest. */
    private static Logger capturingInfo(List<String> sink) {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(), new Class<?>[]{Logger.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("info") && args != null && args.length > 0
                            && args[0] instanceof String fmt) {
                        Object[] rest = args.length == 2 && args[1] instanceof Object[] arr
                                ? arr : Arrays.copyOfRange(args, 1, args.length);
                        sink.add(MessageFormatter.arrayFormat(fmt, rest).getMessage());
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return true;
                    if (rt == int.class) return System.identityHashCode(proxy);
                    if (rt == String.class) return "capture";
                    return null;
                });
    }

    /** A de_DE default locale used to log {@code 1,5 KB}. */
    @Test
    void humanBytesIgnoresTheDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1.5 KB", LoggingProgressListener.humanBytes(1536));
            assertEquals("2.5 MB", LoggingProgressListener.humanBytes(5L * 512 * 1024));
            assertEquals("512 B", LoggingProgressListener.humanBytes(512));
        } finally {
            Locale.setDefault(saved);
        }
    }
}

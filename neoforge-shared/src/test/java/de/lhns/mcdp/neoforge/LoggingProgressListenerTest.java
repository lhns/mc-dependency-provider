package de.lhns.mcdp.neoforge;

import net.neoforged.fml.loading.progress.StartupNotificationManager;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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

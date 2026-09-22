package de.lhns.mcdp.forge;

import net.minecraftforge.forgespi.language.IConfigurable;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Dynamic-proxy stand-ins for the forgespi interfaces the adapter reads. The real
 * implementations live in {@code fmlloader} and need a booted FML to construct.
 *
 * <p>Only the methods a test names are answered; a default interface method falls through to
 * its default body ({@link InvocationHandler#invokeDefault}), and anything else throws, so a
 * production change that starts calling a new SPI method shows up as a test failure naming it
 * rather than as a silent {@code null}.
 *
 * <p>Java 16 source: forge-1.17 compiles this test source set with {@code --release 16}.
 */
final class ForgeStubs {

    private ForgeStubs() {}

    /** A proxy of {@code iface} answering the named methods; see the class comment. */
    static <T> T proxy(Class<T> iface, Map<String, Function<Object[], Object>> answers) {
        String label = "Stub" + iface.getSimpleName();
        InvocationHandler h = (proxy, method, args) -> {
            Function<Object[], Object> answer = answers.get(method.getName());
            if (answer != null) return answer.apply(args);
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            switch (method.getName()) {
                case "toString":
                    return label + answers.keySet();
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException(
                            label + " does not answer " + method.getName());
            }
        };
        return iface.cast(Proxy.newProxyInstance(
                ForgeStubs.class.getClassLoader(), new Class<?>[] { iface }, h));
    }

    /**
     * {@code IModFile.findResource} is varargs ({@code String...}) on every forgespi line these
     * bands use, so the proxy sees one {@code String[]} argument.
     */
    static String firstName(Object[] args) {
        Object a = args[0];
        return (a instanceof String[] names) ? names[0] : (String) a;
    }

    static IModFile modFile(Path filePath, Function<String, Path> findResource) {
        Map<String, Function<Object[], Object>> m = new HashMap<>();
        m.put("getFilePath", a -> filePath);
        m.put("findResource", a -> findResource.apply(firstName(a)));
        return proxy(IModFile.class, m);
    }

    static IModFileInfo fileInfo(IModFile file, List<IModFileInfo.LanguageSpec> languages) {
        Map<String, Function<Object[], Object>> m = new HashMap<>();
        m.put("getFile", a -> file);
        m.put("requiredLanguageLoaders", a -> languages);
        // Read by fmlcore 1.18+'s ModContainer constructor when mods.toml has no displayTest.
        m.put("getFileProperties", a -> Map.of());
        m.put("getConfig", a -> emptyConfig());
        return proxy(IModFileInfo.class, m);
    }

    static IModInfo modInfo(String modId, IModFileInfo owningFile) {
        Map<String, Function<Object[], Object>> m = new HashMap<>();
        m.put("getModId", a -> modId);
        m.put("getNamespace", a -> modId);
        m.put("getOwningFile", a -> owningFile);
        // fmlcore 1.18+ ModContainer(IModInfo) reads getConfig().getConfigElement("displayTest").
        m.put("getConfig", a -> emptyConfig());
        return proxy(IModInfo.class, m);
    }

    /** An {@code IConfigurable} with no keys: mods.toml declared nothing optional. */
    static IConfigurable emptyConfig() {
        Map<String, Function<Object[], Object>> m = new HashMap<>();
        m.put("getConfigElement", a -> Optional.empty());
        m.put("getConfigList", a -> List.of());
        return proxy(IConfigurable.class, m);
    }

    /**
     * A mod whose mod file is the directory {@code root}, resolving resources against it — the
     * dev-run shape. {@code root} gets a {@code META-INF/mcdepprovider.toml} with {@code toml}
     * unless that is null.
     */
    static IModInfo modInDirectory(String modId, Path root, String toml) throws IOException {
        Files.createDirectories(root);
        if (toml != null) writeManifest(root, toml);
        IModFile file = modFile(root, root::resolve);
        return modInfo(modId, fileInfo(file, List.of()));
    }

    static void writeManifest(Path root, String toml) throws IOException {
        Path meta = Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(meta.resolve("mcdepprovider.toml"), toml, StandardCharsets.UTF_8);
    }

    static String langOnly(String lang) {
        return "lang = \"" + lang + "\"\n";
    }

    /** 64 hex characters: the only shape {@code Manifest.Library} accepts. */
    static String sha(char c) {
        return String.valueOf(c).repeat(64);
    }

    static String library(String coords, String sha256) {
        return "\n[[libraries]]\n"
                + "coords = \"" + coords + "\"\n"
                + "url    = \"https://repo1.maven.org/maven2/unused.jar\"\n"
                + "sha256 = \"" + sha256 + "\"\n";
    }

    /** Collects what the adapter logs to its JUL logger while open. */
    static final class LogCapture extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger("mcdepprovider");
        final List<LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();

        LogCapture() {
            logger.addHandler(this);
        }

        boolean anyWarningMentions(String text) {
            for (LogRecord r : records) {
                if (r.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()
                        && r.getMessage() != null && r.getMessage().contains(text)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }
}

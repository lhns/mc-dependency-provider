package de.lhns.mcdp.deps;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TOML serialization for {@link Manifest}. The schema:
 *
 * <pre>
 * lang = "scala"
 * shared_packages = ["com.example.api"]
 *
 * [[libraries]]
 * coords = "org.typelevel:cats-core_3:2.13.0"
 * url    = "https://..."
 * sha256 = "..."
 * </pre>
 */
public final class ManifestIo {

    private ManifestIo() {}

    public static Manifest read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }

    @SuppressWarnings("unchecked")
    public static Manifest read(InputStream in) throws IOException {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> root;
        try {
            root = MiniToml.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid manifest TOML: " + e.getMessage(), e);
        }

        String lang = requireScalar(root, "lang");

        List<String> sharedPackages = new ArrayList<>();
        Object sp = root.get("shared_packages");
        if (sp instanceof List<?> list) {
            for (Object o : list) sharedPackages.add((String) o);
        }

        List<Manifest.Library> libraries = new ArrayList<>();
        Object libs = root.get("libraries");
        if (libs instanceof List<?> list) {
            for (Object o : list) {
                Map<String, String> lib = (Map<String, String>) o;
                libraries.add(new Manifest.Library(
                        requireKey(lib, "coords"),
                        requireKey(lib, "url"),
                        requireKey(lib, "sha256")));
            }
        }

        return new Manifest(lang, sharedPackages, libraries);
    }

    public static void write(Manifest manifest, Path file) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            write(manifest, w);
        }
    }

    public static void write(Manifest manifest, Writer out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("lang = ").append(tomlString(manifest.lang())).append('\n');

        if (!manifest.sharedPackages().isEmpty()) {
            sb.append("shared_packages = [");
            for (int i = 0; i < manifest.sharedPackages().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(tomlString(manifest.sharedPackages().get(i)));
            }
            sb.append("]\n");
        }

        for (Manifest.Library lib : manifest.libraries()) {
            sb.append('\n');
            sb.append("[[libraries]]\n");
            sb.append("coords = ").append(tomlString(lib.coords())).append('\n');
            sb.append("url    = ").append(tomlString(lib.url())).append('\n');
            sb.append("sha256 = ").append(tomlString(lib.sha256())).append('\n');
        }

        out.write(sb.toString());
    }

    public static String toString(Manifest manifest) {
        java.io.StringWriter sw = new java.io.StringWriter();
        try {
            write(manifest, sw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sw.toString();
    }

    private static String requireScalar(Map<String, Object> table, String key) throws IOException {
        Object v = table.get(key);
        if (!(v instanceof String s)) {
            throw new IOException("Missing required key: " + key);
        }
        return s;
    }

    private static String requireKey(Map<String, String> table, String key) throws IOException {
        String v = table.get(key);
        if (v == null) {
            throw new IOException("Missing required key: " + key);
        }
        return v;
    }

    private static String tomlString(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}

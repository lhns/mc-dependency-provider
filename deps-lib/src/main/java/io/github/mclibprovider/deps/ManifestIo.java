package io.github.mclibprovider.deps;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    public static Manifest read(InputStream in) throws IOException {
        TomlParseResult toml = Toml.parse(in);
        if (toml.hasErrors()) {
            throw new IOException("Invalid manifest TOML: " + toml.errors().get(0).toString());
        }

        String lang = requireString(toml, "lang");

        List<String> sharedPackages = new ArrayList<>();
        TomlArray spArray = toml.getArray("shared_packages");
        if (spArray != null) {
            for (int i = 0; i < spArray.size(); i++) {
                sharedPackages.add(spArray.getString(i));
            }
        }

        List<Manifest.Library> libraries = new ArrayList<>();
        TomlArray libArray = toml.getArray("libraries");
        if (libArray != null) {
            for (int i = 0; i < libArray.size(); i++) {
                TomlTable lib = libArray.getTable(i);
                libraries.add(new Manifest.Library(
                        requireString(lib, "coords"),
                        requireString(lib, "url"),
                        requireString(lib, "sha256")));
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

    private static String requireString(TomlTable table, String key) throws IOException {
        String v = table.getString(key);
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

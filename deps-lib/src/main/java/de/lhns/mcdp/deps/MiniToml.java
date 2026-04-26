package de.lhns.mcdp.deps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal TOML reader for the closed schema mc-lib-provider's {@link Manifest} writes. Handles:
 * <ul>
 *   <li>Top-level scalar string assignments: {@code key = "value"}.</li>
 *   <li>Top-level string-array assignments: {@code key = ["a", "b"]} or {@code key = []}.</li>
 *   <li>Array-of-tables markers: {@code [[libraries]]} followed by string {@code key = value}
 *       lines accumulated into a {@code Map<String,String>} until the next section header.</li>
 *   <li>Hash-comments to end-of-line and blank lines.</li>
 *   <li>Standard string escapes the writer emits: backslash-quote, backslash-backslash, n, r, t,
 *       and 4-hex-digit unicode escapes.</li>
 * </ul>
 *
 * Out of scope (not used by {@link ManifestIo#write}): nested tables, inline tables, dotted keys,
 * literal strings, multi-line strings, numbers, booleans, datetimes, mixed-type arrays. The Mixin-
 * config scanner has its own JSON via {@link de.lhns.mcdp.core.MiniJson}; same reasoning, different
 * format.
 *
 * Errors throw {@link IllegalArgumentException} with {@code @line N} positional context so
 * {@link ManifestIo#read} can wrap them in {@code IOException("Invalid manifest TOML: …")}.
 */
final class MiniToml {

    static final String LIBRARIES_KEY = "libraries";

    private MiniToml() {}

    /**
     * Parses {@code source} and returns a top-level map. Values are one of:
     * <ul>
     *   <li>{@link String} — a scalar TOML string.</li>
     *   <li>{@link List}{@code <}{@link String}{@code >} — a TOML string array.</li>
     *   <li>{@link List}{@code <}{@link Map}{@code <}{@link String}{@code ,}{@link String}{@code >>}
     *       — an array-of-tables (only {@code [[libraries]]} appears in our schema).</li>
     * </ul>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Map<String, Object> parse(String source) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, String> currentTable = null;          // non-null while inside [[libraries]]
        String currentArrayKey = null;
        List<Map<String, String>> currentArray = null;

        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String raw = lines[i];
            String line = stripComment(raw).strip();
            if (line.isEmpty()) continue;

            if (line.startsWith("[[")) {
                // Section header: close the previous table (if any), open a new one.
                if (!line.endsWith("]]")) {
                    throw err(lineNo, "unterminated array-of-tables header: " + line);
                }
                String name = line.substring(2, line.length() - 2).strip();
                if (name.isEmpty()) throw err(lineNo, "empty array-of-tables name");

                // Commit the previous table by virtue of holding a reference to it inside the array.
                Object existing = root.get(name);
                if (existing == null) {
                    currentArray = new ArrayList<>();
                    root.put(name, currentArray);
                    currentArrayKey = name;
                } else if (existing instanceof List<?> l && (l.isEmpty() || l.get(0) instanceof Map)) {
                    currentArray = (List<Map<String, String>>) (List) l;
                    currentArrayKey = name;
                } else {
                    throw err(lineNo, "key '" + name + "' redeclared as array-of-tables");
                }
                currentTable = new LinkedHashMap<>();
                currentArray.add(currentTable);
                continue;
            }

            if (line.startsWith("[")) {
                throw err(lineNo, "single-bracket tables not supported in this schema: " + line);
            }

            int eq = indexOfTopLevelEquals(line);
            if (eq < 0) throw err(lineNo, "expected 'key = value' or '[[name]]', got: " + line);
            String key = line.substring(0, eq).strip();
            String valueText = line.substring(eq + 1).strip();
            if (key.isEmpty()) throw err(lineNo, "empty key");

            if (currentTable != null) {
                // Inside a [[…]] table — values must be scalar strings (the schema's three fields).
                String s = parseScalarString(valueText, lineNo);
                currentTable.put(key, s);
            } else {
                // Top-level: scalar string OR string array.
                if (valueText.startsWith("[")) {
                    List<String> arr = parseStringArray(valueText, lineNo);
                    root.put(key, arr);
                } else {
                    String s = parseScalarString(valueText, lineNo);
                    root.put(key, s);
                }
            }
            // Reference unused; reset for clarity if we ever extend.
            if (false) System.out.println(currentArrayKey);
        }
        return root;
    }

    private static String stripComment(String line) {
        // Naive: a '#' inside a string literal would be misinterpreted. The writer never emits one,
        // and the manifest schema's strings (lang/coords/url/sha256) don't contain '#'. Documented
        // limitation rather than implementing full string-aware tokenization.
        int hash = -1;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (c == '#' && !inString) { hash = i; break; }
        }
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static int indexOfTopLevelEquals(String line) {
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (c == '=' && !inString) return i;
        }
        return -1;
    }

    private static String parseScalarString(String text, int lineNo) {
        if (text.length() < 2 || text.charAt(0) != '"' || text.charAt(text.length() - 1) != '"') {
            throw err(lineNo, "expected double-quoted string, got: " + text);
        }
        return decodeString(text.substring(1, text.length() - 1), lineNo);
    }

    private static List<String> parseStringArray(String text, int lineNo) {
        if (!text.startsWith("[") || !text.endsWith("]")) {
            throw err(lineNo, "expected [\"…\", …], got: " + text);
        }
        String body = text.substring(1, text.length() - 1).strip();
        if (body.isEmpty()) return List.of();

        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < body.length()) {
            // Skip whitespace + commas.
            while (i < body.length() && (Character.isWhitespace(body.charAt(i)) || body.charAt(i) == ',')) i++;
            if (i >= body.length()) break;
            if (body.charAt(i) != '"') throw err(lineNo, "expected '\"' in array at column " + i + " of: " + body);
            // Find matching close-quote, respecting backslash escapes.
            int j = i + 1;
            while (j < body.length()) {
                char c = body.charAt(j);
                if (c == '\\') { j += 2; continue; }
                if (c == '"') break;
                j++;
            }
            if (j >= body.length()) throw err(lineNo, "unterminated string in array: " + body);
            String inner = body.substring(i + 1, j);
            out.add(decodeString(inner, lineNo));
            i = j + 1;
        }
        return out;
    }

    private static String decodeString(String inner, int lineNo) {
        StringBuilder b = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c != '\\') { b.append(c); continue; }
            if (i + 1 >= inner.length()) throw err(lineNo, "trailing backslash");
            char e = inner.charAt(++i);
            switch (e) {
                case '"', '\\' -> b.append(e);
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    if (i + 4 >= inner.length()) throw err(lineNo, "truncated \\u escape");
                    String hex = inner.substring(i + 1, i + 5);
                    try {
                        b.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException nfe) {
                        throw err(lineNo, "bad \\u" + hex);
                    }
                    i += 4;
                }
                default -> throw err(lineNo, "unknown escape \\" + e);
            }
        }
        return b.toString();
    }

    private static IllegalArgumentException err(int lineNo, String message) {
        return new IllegalArgumentException("@line " + lineNo + ": " + message);
    }
}

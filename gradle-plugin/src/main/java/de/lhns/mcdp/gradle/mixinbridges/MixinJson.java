package de.lhns.mcdp.gradle.mixinbridges;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader sized for {@code *.mixins.json} configs. Returns
 * {@code Map<String,Object>} for objects, {@code List<Object>} for arrays, {@link String} for
 * strings, {@link Double} for numbers, {@link Boolean} for booleans, {@code null} for null.
 *
 * <p>Mirror of {@code de.lhns.mcdp.core.MiniJson}; duplicated here because the gradle-plugin
 * module doesn't depend on {@code core}, and exporting MiniJson would add unnecessary public API
 * surface. Both readers consume the same dialect.</p>
 */
final class MixinJson {

    private final String s;
    private int i;

    private MixinJson(String s) {
        this.s = s;
        this.i = 0;
    }

    static Object parse(String s) {
        MixinJson p = new MixinJson(s);
        p.skipWhitespace();
        Object v = p.readValue();
        p.skipWhitespace();
        if (p.i != p.s.length()) throw new RuntimeException("trailing data at offset " + p.i);
        return v;
    }

    private Object readValue() {
        skipWhitespace();
        char c = peek();
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBoolean();
            case 'n' -> readNull();
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') { i++; return m; }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            Object v = readValue();
            m.put(key, v);
            skipWhitespace();
            char c = next();
            if (c == ',') continue;
            if (c == '}') return m;
            throw new RuntimeException("expected ',' or '}' at " + (i - 1));
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') { i++; return list; }
        while (true) {
            Object v = readValue();
            list.add(v);
            skipWhitespace();
            char c = next();
            if (c == ',') continue;
            if (c == ']') return list;
            throw new RuntimeException("expected ',' or ']' at " + (i - 1));
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        int cp = Integer.parseInt(s.substring(i, i + 4), 16);
                        sb.append((char) cp);
                        i += 4;
                    }
                    default -> throw new RuntimeException("bad escape \\" + esc + " at " + (i - 1));
                }
            } else {
                sb.append(c);
            }
        }
        throw new RuntimeException("unterminated string");
    }

    private Boolean readBoolean() {
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        throw new RuntimeException("expected boolean at " + i);
    }

    private Object readNull() {
        if (s.startsWith("null", i)) { i += 4; return null; }
        throw new RuntimeException("expected null at " + i);
    }

    private Double readNumber() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        return Double.parseDouble(s.substring(start, i));
    }

    private void skipWhitespace() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private char peek() { return s.charAt(i); }
    private char next() { return s.charAt(i++); }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) {
            throw new RuntimeException("expected '" + c + "' at " + i);
        }
        i++;
    }
}

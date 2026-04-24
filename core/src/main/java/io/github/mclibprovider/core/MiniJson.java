package io.github.mclibprovider.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader. Returns {@code Map<String,Object>} for objects, {@code List<Object>} for
 * arrays, {@link String} for strings, {@link Double} for numbers, {@link Boolean} for booleans,
 * and {@code null} for nulls.
 *
 * Used by the Fabric + NeoForge adapters to read {@code fabric.mod.json} and each mod's
 * {@code *.mixins.json} configs for {@link McLibProvider#registerMixinOwner} wiring. Adding a full
 * JSON dep (gson, jackson) on the Fabric side would require bundling it into the shaded jar —
 * split-package risk per {@code docs/pitfalls.md}. Since mixin configs are well-formed JSON from
 * trusted sources, a tiny purpose-built parser is the right trade-off.
 *
 * Not exported API. Not a full JSON 1.0 parser — no surrogate-pair handling, no scientific
 * notation beyond what {@link Double#parseDouble} accepts, no comments.
 */
final class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
        this.i = 0;
    }

    static Object parse(String source) {
        MiniJson p = new MiniJson(source);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.i != p.s.length()) throw p.err("trailing content");
        return v;
    }

    private Object value() {
        skipWs();
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't', 'f' -> bool();
            case 'n' -> nullLiteral();
            default -> number();
        };
    }

    private Map<String, Object> object() {
        expect('{');
        skipWs();
        Map<String, Object> out = new LinkedHashMap<>();
        if (peek() == '}') { i++; return out; }
        while (true) {
            skipWs();
            String key = string();
            skipWs();
            expect(':');
            Object v = value();
            out.put(key, v);
            skipWs();
            char c = s.charAt(i++);
            if (c == ',') continue;
            if (c == '}') return out;
            throw err("expected , or }");
        }
    }

    private List<Object> array() {
        expect('[');
        skipWs();
        List<Object> out = new ArrayList<>();
        if (peek() == ']') { i++; return out; }
        while (true) {
            out.add(value());
            skipWs();
            char c = s.charAt(i++);
            if (c == ',') continue;
            if (c == ']') return out;
            throw err("expected , or ]");
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                if (i >= s.length()) throw err("bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw err("bad unicode");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw err("bad escape \\" + e);
                }
            } else {
                b.append(c);
            }
        }
        throw err("unterminated string");
    }

    private Boolean bool() {
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        throw err("expected boolean");
    }

    private Object nullLiteral() {
        if (s.startsWith("null", i)) { i += 4; return null; }
        throw err("expected null");
    }

    private Double number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw err("expected value");
        return Double.parseDouble(s.substring(start, i));
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) throw err("expected " + c);
        i++;
    }

    private char peek() {
        if (i >= s.length()) throw err("unexpected end");
        return s.charAt(i);
    }

    private void skipWs() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("MiniJson @" + i + ": " + msg);
    }
}

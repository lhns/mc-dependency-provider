package io.github.mclibprovider.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MiniJsonTest {

    @Test
    void parsesObjectWithStringsAndArrays() {
        Object r = MiniJson.parse("""
                {"package":"com.example","mixins":["A","B"]}""");
        assertInstanceOf(Map.class, r);
        Map<?, ?> m = (Map<?, ?>) r;
        assertEquals("com.example", m.get("package"));
        assertEquals(List.of("A", "B"), m.get("mixins"));
    }

    @Test
    void parsesNestedObjects() {
        Object r = MiniJson.parse("""
                {"mixins":[{"config":"foo.mixins.json","environment":"client"}]}""");
        Map<?, ?> m = (Map<?, ?>) r;
        List<?> mixins = (List<?>) m.get("mixins");
        Map<?, ?> first = (Map<?, ?>) mixins.get(0);
        assertEquals("foo.mixins.json", first.get("config"));
    }

    @Test
    void handlesEscapesAndUnicode() {
        Object r = MiniJson.parse("\"a\\n\\\"b\\u00e9\"");
        assertEquals("a\n\"bé", r);
    }

    @Test
    void parsesNumbersBooleansAndNulls() {
        Map<?, ?> m = (Map<?, ?>) MiniJson.parse("""
                {"n":1.5,"t":true,"f":false,"z":null}""");
        assertEquals(1.5, m.get("n"));
        assertEquals(Boolean.TRUE, m.get("t"));
        assertEquals(Boolean.FALSE, m.get("f"));
        assertNull(m.get("z"));
        assertTrue(m.containsKey("z"));
    }

    @Test
    void emptyObjectAndArray() {
        assertEquals(Map.of(), MiniJson.parse("{}"));
        assertEquals(List.of(), MiniJson.parse("[]"));
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{}x"));
    }

    @Test
    void rejectsUnterminatedString() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("\"abc"));
    }
}

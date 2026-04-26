package de.lhns.mcdp.deps;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MiniTomlTest {

    @Test
    void parsesScalarStringAndStringArray() {
        Map<String, Object> r = MiniToml.parse("""
                lang = "scala"
                shared_packages = ["a", "b"]
                """);
        assertEquals("scala", r.get("lang"));
        assertEquals(List.of("a", "b"), r.get("shared_packages"));
    }

    @Test
    void parsesEmptyArray() {
        Map<String, Object> r = MiniToml.parse("shared_packages = []\n");
        assertEquals(List.of(), r.get("shared_packages"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesArrayOfTables() {
        Map<String, Object> r = MiniToml.parse("""
                lang = "java"

                [[libraries]]
                coords = "a:b:1"
                url    = "https://x/y.jar"
                sha256 = "deadbeef"

                [[libraries]]
                coords = "c:d:2"
                url    = "https://x/z.jar"
                sha256 = "cafebabe"
                """);
        List<Map<String, String>> libs = (List<Map<String, String>>) r.get("libraries");
        assertEquals(2, libs.size());
        assertEquals("a:b:1", libs.get(0).get("coords"));
        assertEquals("https://x/y.jar", libs.get(0).get("url"));
        assertEquals("deadbeef", libs.get(0).get("sha256"));
        assertEquals("c:d:2", libs.get(1).get("coords"));
    }

    @Test
    void preservesArrayOfTablesOrder() {
        Map<String, Object> r = MiniToml.parse("""
                [[libraries]]
                coords = "first"
                url    = "u"
                sha256 = "s"

                [[libraries]]
                coords = "second"
                url    = "u"
                sha256 = "s"

                [[libraries]]
                coords = "third"
                url    = "u"
                sha256 = "s"
                """);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> libs = (List<Map<String, String>>) r.get("libraries");
        assertEquals("first", libs.get(0).get("coords"));
        assertEquals("second", libs.get(1).get("coords"));
        assertEquals("third", libs.get(2).get("coords"));
    }

    @Test
    void handlesEscapesInStrings() {
        Map<String, Object> r = MiniToml.parse("""
                a = "line1\\nline2"
                b = "tab\\there"
                c = "quote: \\"x\\""
                d = "unicode-\\u00e9"
                """);
        assertEquals("line1\nline2", r.get("a"));
        assertEquals("tab\there", r.get("b"));
        assertEquals("quote: \"x\"", r.get("c"));
        assertEquals("unicode-é", r.get("d"));
    }

    @Test
    void stripsCommentsAndBlankLines() {
        Map<String, Object> r = MiniToml.parse("""
                # leading comment
                lang = "java"  # trailing comment

                # blank line above and below

                [[libraries]]
                coords = "a:b:1"
                url    = "u"
                sha256 = "s"
                """);
        assertEquals("java", r.get("lang"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> libs = (List<Map<String, String>>) r.get("libraries");
        assertEquals(1, libs.size());
    }

    @Test
    void rejectsUnterminatedArrayOfTables() {
        assertThrows(IllegalArgumentException.class, () -> MiniToml.parse("[[libraries\n"));
    }

    @Test
    void rejectsUnterminatedString() {
        assertThrows(IllegalArgumentException.class, () -> MiniToml.parse("lang = \"scala\n"));
    }

    @Test
    void rejectsBareLineWithoutEquals() {
        assertThrows(IllegalArgumentException.class, () -> MiniToml.parse("just a bare line\n"));
    }

    @Test
    void rejectsEmptyKey() {
        assertThrows(IllegalArgumentException.class, () -> MiniToml.parse(" = \"x\"\n"));
    }

    @Test
    void rejectsSingleBracketTable() {
        // Out of scope for our schema. Loud failure beats silent acceptance.
        assertThrows(IllegalArgumentException.class, () -> MiniToml.parse("[section]\n"));
    }

    @Test
    void emptyInputProducesEmptyMap() {
        assertTrue(MiniToml.parse("").isEmpty());
        assertTrue(MiniToml.parse("\n\n# only comments\n\n").isEmpty());
    }
}

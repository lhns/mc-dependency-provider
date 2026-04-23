package io.github.mclibprovider.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntrypointAdapterTest {

    // --- Java ---

    public static class JavaNoArg {
        public JavaNoArg() {}
    }

    public static class JavaOneArg {
        final String s;
        public JavaOneArg(String s) { this.s = s; }
    }

    public static class JavaTwoArg {
        final String s;
        final Integer i;
        public JavaTwoArg(String s, Integer i) { this.s = s; this.i = i; }
    }

    @Test
    void javaAdapterPicksHighestMatchingArity() throws Exception {
        var adapter = new JavaEntrypointAdapter();

        assertInstanceOf(JavaNoArg.class, adapter.construct(JavaNoArg.class, "unused", 1, 2.0));

        JavaOneArg one = (JavaOneArg) adapter.construct(JavaOneArg.class, "hello", null, null);
        assertEquals("hello", one.s);

        JavaTwoArg two = (JavaTwoArg) adapter.construct(JavaTwoArg.class, "hi", 42, null);
        assertEquals("hi", two.s);
        assertEquals(42, two.i);
    }

    @Test
    void javaAdapterThrowsWhenNoCtorMatches() {
        assertThrows(NoSuchMethodException.class,
                () -> new JavaEntrypointAdapter().construct(JavaOneArg.class, 123)); // wrong type
    }

    // --- Scala ---

    /** Simulates a Scala {@code object} — compilers emit a {@code MODULE$} static final field. */
    public static class ScalaObject {
        public static final ScalaObject MODULE$ = new ScalaObject();
        private ScalaObject() {}
    }

    public static class ScalaClass {
        public final String name;
        public ScalaClass() { this.name = "default"; }
        public ScalaClass(String name) { this.name = name; }
    }

    @Test
    void scalaAdapterReturnsModuleSingleton() throws Exception {
        Object instance = new ScalaEntrypointAdapter().construct(ScalaObject.class);
        assertSame(ScalaObject.MODULE$, instance);
    }

    @Test
    void scalaAdapterFallsBackToCtorChain() throws Exception {
        ScalaClass inst = (ScalaClass) new ScalaEntrypointAdapter().construct(ScalaClass.class, "custom");
        assertEquals("custom", inst.name);
    }

    // --- Kotlin ---

    /** Simulates a Kotlin {@code object} — compilers emit an {@code INSTANCE} static final field. */
    public static class KotlinObject {
        public static final KotlinObject INSTANCE = new KotlinObject();
        private KotlinObject() {}
    }

    @Test
    void kotlinAdapterReturnsInstanceSingleton() throws Exception {
        Object instance = new KotlinEntrypointAdapter().construct(KotlinObject.class);
        assertSame(KotlinObject.INSTANCE, instance);
    }

    // --- dispatch ---

    @Test
    void forLangMapsToImplementations() {
        assertInstanceOf(JavaEntrypointAdapter.class, EntrypointAdapter.forLang("java"));
        assertInstanceOf(ScalaEntrypointAdapter.class, EntrypointAdapter.forLang("scala"));
        assertInstanceOf(KotlinEntrypointAdapter.class, EntrypointAdapter.forLang("kotlin"));
        assertThrows(IllegalArgumentException.class, () -> EntrypointAdapter.forLang("clojure"));
    }
}

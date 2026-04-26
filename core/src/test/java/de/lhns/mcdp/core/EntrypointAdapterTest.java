package de.lhns.mcdp.core;

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

    // --- Bag-based dispatch (typed-by-class, order-independent) ---

    interface Bus {}
    interface Info {}
    interface DistMarker {}

    public static class BusOnly {
        public final Bus bus;
        public BusOnly(Bus bus) { this.bus = bus; }
    }
    public static class InfoOnly {
        public final Info info;
        public InfoOnly(Info info) { this.info = info; }
    }
    public static class InfoBus {
        public final Info info; public final Bus bus;
        public InfoBus(Info info, Bus bus) { this.info = info; this.bus = bus; }
    }
    public static class BusInfo {
        public final Bus bus; public final Info info;
        public BusInfo(Bus bus, Info info) { this.bus = bus; this.info = info; }
    }
    public static class BusDist {
        public final Bus bus; public final DistMarker dist;
        public BusDist(Bus bus, DistMarker dist) { this.bus = bus; this.dist = dist; }
    }
    public static class MultiCtor {
        public final int chosen;
        public MultiCtor() { this.chosen = 0; }
        public MultiCtor(Bus bus) { this.chosen = 1; }
        public MultiCtor(Info info, Bus bus, DistMarker dist) { this.chosen = 3; }
    }

    private static final Bus BUS = new Bus() {};
    private static final Info INFO = new Info() {};
    private static final DistMarker DIST = new DistMarker() {};

    @Test
    void bagFillsBusOnlyCtor() throws Exception {
        BusOnly r = (BusOnly) new JavaEntrypointAdapter().construct(BusOnly.class, INFO, BUS, DIST);
        assertSame(BUS, r.bus);
    }

    @Test
    void bagFillsInfoOnlyCtor() throws Exception {
        InfoOnly r = (InfoOnly) new JavaEntrypointAdapter().construct(InfoOnly.class, INFO, BUS, DIST);
        assertSame(INFO, r.info);
    }

    @Test
    void bagFillsBothInEitherOrder() throws Exception {
        InfoBus a = (InfoBus) new JavaEntrypointAdapter().construct(InfoBus.class, INFO, BUS, DIST);
        assertSame(INFO, a.info);
        assertSame(BUS, a.bus);

        BusInfo b = (BusInfo) new JavaEntrypointAdapter().construct(BusInfo.class, INFO, BUS, DIST);
        assertSame(BUS, b.bus);
        assertSame(INFO, b.info);
    }

    @Test
    void bagPicksMostSpecificCtor() throws Exception {
        // All three ctors are reachable; the 3-arg one should win because it consumes more
        // of the bag, and falls back gracefully when bag entries are missing.
        MultiCtor full = (MultiCtor) new JavaEntrypointAdapter().construct(MultiCtor.class, INFO, BUS, DIST);
        assertEquals(3, full.chosen);

        MultiCtor noInfo = (MultiCtor) new JavaEntrypointAdapter().construct(MultiCtor.class, BUS);
        assertEquals(1, noInfo.chosen);

        MultiCtor empty = (MultiCtor) new JavaEntrypointAdapter().construct(MultiCtor.class);
        assertEquals(0, empty.chosen);
    }

    @Test
    void bagThrowsWhenNeededTypeIsMissing() {
        assertThrows(NoSuchMethodException.class,
                () -> new JavaEntrypointAdapter().construct(BusDist.class, INFO, BUS)); // no Dist
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

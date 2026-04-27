package de.lhns.mcdp.api;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test stub on the gradle-plugin test classpath. Mirrors the {@code resolveAutoBridgeImpl}
 * surface that the rewriter now emits {@code <clinit>} calls to. The real one lives in
 * {@code :core}, which the gradle-plugin module deliberately does not depend on (see
 * {@code BridgePolicy}). Tests register the impl instance directly here and trigger class
 * init to drive the wiring through this stub.
 */
public final class McdpProvider {

    private McdpProvider() {}

    private static final Map<String, Object> STUB_REGISTRY = new ConcurrentHashMap<>();

    public static Object resolveAutoBridgeImpl(String mixinFqn, String fieldName) {
        String key = mixinFqn + "#" + fieldName;
        Object o = STUB_REGISTRY.get(key);
        if (o == null) {
            throw new IllegalStateException("test stub: no registration for " + key);
        }
        return o;
    }

    /** Test hook — register an impl instance that the emitted clinit will hand to LOGIC_*. */
    public static void registerForTest(String mixinFqn, String fieldName, Object instance) {
        STUB_REGISTRY.put(mixinFqn + "#" + fieldName, Objects.requireNonNull(instance));
    }

    public static void clearForTest() {
        STUB_REGISTRY.clear();
    }
}

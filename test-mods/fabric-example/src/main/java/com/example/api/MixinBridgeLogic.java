package com.example.api;

/**
 * Bridge contract for the Mixin smoke (#23 / ADR-0008).
 *
 * Lives under {@code com.example.api} — declared in {@code sharedPackages} in the plugin
 * extension so the mod's {@code ModClassLoader} delegates parent-first for this prefix.
 * That preserves Class identity across:
 *   (a) the Minecraft TransformingClassLoader, which loads the Java mixin class and
 *       binds to this interface at mixin-application time; and
 *   (b) the per-mod ModClassLoader, which loads the Scala impl that implements it.
 * Without parent-first delegation the two loaders would define two distinct
 * {@code MixinBridgeLogic} {@code Class} objects and the cast inside the mixin would fail.
 */
public interface MixinBridgeLogic {
    void onServerTick();

    long invocationCount();
}

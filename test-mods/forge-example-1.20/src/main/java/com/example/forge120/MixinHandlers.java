package com.example.forge120;

/**
 * Mod-private mixin callback target. Lives on the mod's own {@code ModClassLoader}, so the
 * mixin — which is merged into a Minecraft class on the platform loader — cannot call it
 * directly. That is exactly what forces the ADR-0018 codegen to emit an auto-bridge, and what
 * makes this mod the Forge-side smoke for the bridge registry.
 */
public final class MixinHandlers {

    private MixinHandlers() {}

    public static void onBlocksClinit() {
        SmokeLog.emit("mixin=BlocksClinitMixin ok");
    }
}

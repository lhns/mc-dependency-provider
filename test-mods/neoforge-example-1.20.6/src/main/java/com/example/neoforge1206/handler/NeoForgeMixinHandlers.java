package com.example.neoforge1206.handler;

/**
 * Mod-private mixin callback target, on the mod's own {@code ModClassLoader}. This mod shares no
 * package with the platform, so the mixin — merged into a Minecraft class on the game loader —
 * cannot call this directly; that is what forces the ADR-0018 codegen to emit an auto-bridge,
 * and what makes the marker below evidence that the bridge registry resolved.
 */
public final class NeoForgeMixinHandlers {

    private NeoForgeMixinHandlers() {}

    public static void onBlocksClinit() {
        System.out.println("[mcdp-smoke] mixin=BlocksClinitMixin mod=neoforge_example_1206 ok");
    }
}

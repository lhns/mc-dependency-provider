package com.example.handler;

/**
 * Mod-private mixin callback target. Lives on the mod's own {@code ModClassLoader} — not in
 * {@code com.example.api}, the one package this mod shares with the platform — so the mixin,
 * which is merged into a Minecraft class on the game loader, cannot call it directly. That is
 * what forces the ADR-0018 codegen to emit an auto-bridge, and what makes the marker below
 * evidence that the bridge registry resolved.
 *
 * <p>Java, not Scala: scalac's bytecode shape for mixin annotations is its own research project,
 * and this file is here to test mcdp, not scalac. The mcdp Gradle plugin already feeds every
 * compiler output dir to the bridge scanner, so a pure-Java class in a Scala mod is a supported
 * arrangement rather than a workaround.
 */
public final class NeoForgeMixinHandlers {

    private NeoForgeMixinHandlers() {}

    public static void onBlocksClinit() {
        System.out.println("[mcdp-smoke] mixin=BlocksClinitMixin mod=neoforge_example ok");
    }
}

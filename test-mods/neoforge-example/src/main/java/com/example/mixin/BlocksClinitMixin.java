package com.example.mixin;

import com.example.handler.NeoForgeMixinHandlers;
// NeoForge runs on official Mojang names in dev and in production alike, so this mixin needs
// no refmap at all — one fewer moving part than the Forge 1.20 twin.
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First mixin on any NeoForge band. It exists to make three never-exercised paths run for real:
 * FML's {@code [[mixins]]} block reaching {@code McdpLanguageLoader
 * .registerMixinOwnersForNeoForgeMod}, the ADR-0018 auto-bridge on a NeoForge mod, and the
 * ADR-0028 lazy populator firing from a mixin {@code <clinit>} rather than from {@code loadMod}.
 *
 * <p>{@code Blocks.<clinit>} fires inside {@code Bootstrap.bootStrap()}, well before FML's
 * {@code loadMod} sweep, so reaching the marker is positive evidence that the populator ran on
 * demand. {@code <clinit>} is also never remapped, which is what keeps this refmap-free.
 */
@Mixin(Blocks.class)
public abstract class BlocksClinitMixin {

    @Inject(method = "<clinit>", at = @At("HEAD"))
    private static void neoforgeExample$blocksClinitHead(CallbackInfo ci) {
        NeoForgeMixinHandlers.onBlocksClinit();
    }
}

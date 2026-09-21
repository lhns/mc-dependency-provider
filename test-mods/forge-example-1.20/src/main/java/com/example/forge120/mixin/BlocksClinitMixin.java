package com.example.forge120.mixin;

import com.example.forge120.MixinHandlers;
// Official Mojang mappings — 1.17+ dev runs are not SRG-mapped, so no refmap is needed.
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires inside {@code Bootstrap.bootStrap()}, well before FML's {@code loadMod} sweep. If the
 * auto-bridge registry were populated eagerly at container construction the GETSTATIC here would
 * resolve against an empty registry and the server would die during init — so a boot that reaches
 * the marker is positive evidence that the lazy populator (ADR-0028) ran on demand.
 *
 * <p>{@code <clinit>} is never SRG-remapped, which keeps this mixin refmap-free.
 */
@Mixin(Blocks.class)
public abstract class BlocksClinitMixin {

    @Inject(method = "<clinit>", at = @At("HEAD"))
    private static void forgeExample120$blocksClinitHead(CallbackInfo ci) {
        MixinHandlers.onBlocksClinit();
    }
}

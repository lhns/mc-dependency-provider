package com.example.mixinmod.mixin;

import com.example.mixinmod.MixinHandlersA;
// Yarn mappings: net.minecraft.block.Blocks (Mojang's net.minecraft.world.level.block.Blocks).
import net.minecraft.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Early-init regression test (per ADR-0019 / mc-fluid-physics report). Blocks.&lt;clinit&gt;
 * fires once during server init, before MinecraftServer.tick is ever called. If the auto-bridge
 * registry is empty when the mixin's GETSTATIC of LOGIC_* resolves, the server crashes during
 * init and never reaches the smoke marker. This catches the failure mode the other six
 * MinecraftServer.tick mixins cannot — by the time tick runs, the empty-registry crash window
 * is already past.
 */
@Mixin(Blocks.class)
public abstract class BlocksClinitMixin {

    @Inject(method = "<clinit>", at = @At("HEAD"))
    private static void mixinExample$blocksClinitHead(CallbackInfo ci) {
        MixinHandlersA.onMixinBlocksClinit();
    }
}

package com.example.neoforge1206.mixin;

import com.example.neoforge1206.handler.NeoForgeMixinHandlers;
// NeoForge 20.6 runs on official Mojang names in dev and in production alike, so no refmap.
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The 1.20.6 band's bootstrap-time mixin, copied from test-mods/neoforge-example.
 *
 * <p>{@code Blocks.<clinit>} fires inside {@code Bootstrap.bootStrap()}, before FML's
 * {@code loadMod} sweep, so the auto-bridge this mixin calls through can only resolve if the
 * adapter's lazy populator registers the mod on demand. That populator is the path the 1.20.6
 * band once compiled against the wrong SPI ({@code IModFile.getLoaders()}, absent from loader
 * 3.0.45 — a {@code NoSuchMethodError} at boot); until this mixin, nothing in CI ran it.
 */
@Mixin(Blocks.class)
public abstract class BlocksClinitMixin {

    @Inject(method = "<clinit>", at = @At("HEAD"))
    private static void neoforgeExample1206$blocksClinitHead(CallbackInfo ci) {
        NeoForgeMixinHandlers.onBlocksClinit();
    }
}

package com.example.mixin;

import com.example.api.MixinBridgeLogic;
import io.github.mclibprovider.api.McLibMixin;
import io.github.mclibprovider.api.McLibProvider;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin smoke (#23 / ADR-0008).
 *
 * Sits on Minecraft's TransformingClassLoader (standard mixin placement). @McLibMixin
 * names a Scala impl class loaded through fabric-example's own ModClassLoader via
 * {@link McLibProvider#loadMixinImpl}. The logic field holds the impl as a
 * {@link MixinBridgeLogic} — identity-preserved across both loaders because
 * {@code com.example.api} is in {@code sharedPackages}.
 *
 * Targeting {@code MinecraftServer#tick} fires every tick; the impl only prints on
 * the first invocation, keeping log noise bounded while still proving the path.
 */
@Mixin(MinecraftServer.class)
@McLibMixin(impl = "com.example.impl.MixinBridgeLogicScala")
public abstract class MinecraftServerMixin {

    private static final MixinBridgeLogic LOGIC = McLibProvider.loadMixinImpl(MixinBridgeLogic.class);

    @Inject(method = "tick", at = @At("HEAD"))
    private void mcLibProviderSmoke$onTick(CallbackInfo ci) {
        LOGIC.onServerTick();
    }
}

package com.example.mixinmod.mixin;

import com.example.mixinmod.MixinHandlersA;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerThreeMixin {

    @Inject(method = "tick", at = @At("RETURN"))
    private void mixinExample$three(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        MixinHandlersA.onMixinThree();
    }
}

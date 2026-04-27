package com.example.mixinmod.mixin;

import com.example.mixinmod.MixinHandlersA;
import com.example.mixinmod.MixinHandlersB;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Multi-target regression test (per the mc-fluid-physics AbstractBlockStateMixin shape). One
 * mixin method body calls into TWO mod-private types — codegen produces TWO {@code [[bridge]]}
 * entries for this single mixin (one for {@code MixinHandlersA}, one for
 * {@code MixinHandlersB}). Both LOGIC fields' {@code <clinit>} paths must populate at runtime;
 * both smoke markers must print on the first tick.
 */
@Mixin(MinecraftServer.class)
public abstract class MultiTargetMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void mixinExample$multiTarget(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        MixinHandlersA.onMixinMultiTargetA();
        MixinHandlersB.onMixinMultiTargetB();
    }
}

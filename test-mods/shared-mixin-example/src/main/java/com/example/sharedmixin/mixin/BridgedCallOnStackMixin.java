package com.example.sharedmixin.mixin;

import com.example.sharedmixin.internal.Handlers;
import com.example.sharedmixin.internal.SmokeLog;
import net.minecraft.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shape 2 — the path that already works: a bridged call whose result is consumed directly off
 * the stack by the next bridged call, never stored. This is all scalac ever emits, so it is the
 * only shape the existing mixin-example covers. It is here as a regression canary: if this
 * marker stops printing, the working path broke.
 *
 * <p>Target is {@code Blocks.<clinit>}, as in mixin-example: {@code <clinit>} is never remapped,
 * so no refmap is needed, and it fires during server init — before the auto-bridge registry
 * would be populated by anything later.</p>
 */
@Mixin(Blocks.class)
public abstract class BridgedCallOnStackMixin {

    @Inject(method = "<clinit>", at = @At("HEAD"))
    private static void sharedMixin$callOnStack(CallbackInfo ci) {
        SmokeLog.emit(Handlers.config("bridged-call-on-stack").shape());
    }
}

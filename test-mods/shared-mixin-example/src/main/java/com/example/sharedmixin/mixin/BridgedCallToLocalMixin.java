package com.example.sharedmixin.mixin;

import com.example.sharedmixin.internal.Cfg;
import com.example.sharedmixin.internal.Handlers;
import com.example.sharedmixin.internal.SmokeLog;
import net.minecraft.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shape 1 — the LVT shape. Identical to {@link BridgedCallOnStackMixin} except that the bridged
 * call's result is bound to a local before use. javac then emits a LocalVariableTable entry
 * whose descriptor names {@code Cfg}, a mod-private type; the ADR-0018 rewriter rewrites the
 * call instruction but leaves the LVT alone, and ClassRefCollector reads LVT descriptors with no
 * bridge exemption. Because this class lives in a shared package, validator A sees that leftover
 * descriptor. Nothing at runtime resolves it — the LVT is debug metadata — so the diagnostic is
 * a false positive, and it is why mc-fluid-physics could not build.
 *
 * <p>One shape per mixin class, not merely per method: a mixin failure is per-class, so putting
 * all three in one class would let the first failure hide the other two.</p>
 */
@Mixin(Blocks.class)
public abstract class BridgedCallToLocalMixin {

    @Inject(method = "<clinit>", at = @At("HEAD"))
    private static void sharedMixin$callToLocal(CallbackInfo ci) {
        Cfg cfg = Handlers.config("bridged-call-to-local");
        SmokeLog.emit(cfg.shape());
    }
}

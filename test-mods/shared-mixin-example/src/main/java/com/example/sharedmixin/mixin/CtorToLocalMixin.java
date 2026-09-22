package com.example.sharedmixin.mixin;

import com.example.sharedmixin.internal.Cfg;
import com.example.sharedmixin.internal.SmokeLog;
import net.minecraft.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shape 3 — constructing a mod-private type into a local, then calling on it. javac emits
 * NEW/DUP/INVOKESPECIAL/ASTORE/ALOAD with no CHECKCAST, so the scanner's locals-typing check
 * (CHECKCAST/INSTANCEOF/ANEWARRAY only) never sees it: the constructor bridge's return type and
 * the following bridge call's receiver type have to agree on their own, and the verifier checks
 * that at mixin-apply time rather than at build time. This class also carries the shape-1 LVT
 * entry, since the local is typed {@code Cfg} here too.
 */
@Mixin(Blocks.class)
public abstract class CtorToLocalMixin {

    @Inject(method = "<clinit>", at = @At("HEAD"))
    private static void sharedMixin$ctorToLocal(CallbackInfo ci) {
        Cfg cfg = new Cfg("ctor-to-local");
        SmokeLog.emit(cfg.shape());
    }
}

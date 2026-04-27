package com.example.mixinmod;

public final class MixinHandlersB {

    private MixinHandlersB() {}

    public static void onMixinFour() { SmokeLog.emit("MinecraftServerFourMixin"); }
    public static void onMixinFive() { SmokeLog.emit("MinecraftServerFiveMixin"); }
}

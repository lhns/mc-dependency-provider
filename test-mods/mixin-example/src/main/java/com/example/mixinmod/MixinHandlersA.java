package com.example.mixinmod;

public final class MixinHandlersA {

    private MixinHandlersA() {}

    public static void onMixinOne() { SmokeLog.emit("MinecraftServerOneMixin"); }
    public static void onMixinTwo() { SmokeLog.emit("MinecraftServerTwoMixin"); }
    public static void onMixinThree() { SmokeLog.emit("MinecraftServerThreeMixin"); }
}

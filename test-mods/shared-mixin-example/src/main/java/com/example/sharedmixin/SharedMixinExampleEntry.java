package com.example.sharedmixin;

import net.fabricmc.api.ModInitializer;

/**
 * Entry point, loaded through the per-mod {@code ModClassLoader} by the mcdepprovider
 * entrypoint adapter. Lives outside {@code com.example.sharedmixin.mixin} — the only shared
 * package here — so it is mod-private like everything except the mixins themselves.
 */
public final class SharedMixinExampleEntry implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("[mcdp-smoke] mod=shared_mixin_example boot ok");
    }
}

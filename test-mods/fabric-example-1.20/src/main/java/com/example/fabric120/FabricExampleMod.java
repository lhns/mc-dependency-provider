package com.example.fabric120;

import net.fabricmc.api.ModInitializer;
import org.apache.commons.lang3.StringUtils;

/**
 * Minimal Fabric 1.20.1 mod loaded by mcdepprovider. Verifies the same end-to-end
 * pipeline as the 1.21 fabric-example, but on the older band — confirming that the
 * fabric-1.20 sub-project's source share with fabric/ produces a runtime that
 * actually boots on Java 17 + fabric-loader 0.15.x.
 */
public final class FabricExampleMod implements ModInitializer {
    @Override
    public void onInitialize() {
        String banner = StringUtils.center(" mcdp-1.20 boot ok ", 60, "=");
        System.out.println("[mcdp-smoke] " + banner);
        System.out.println("[mcdp-smoke] mod=fabric_example_120 boot ok");
    }
}

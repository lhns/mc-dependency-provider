package com.example.fabric12111;

import net.fabricmc.api.ModInitializer;
import org.apache.commons.lang3.StringUtils;

public final class FabricExampleMod implements ModInitializer {
    @Override
    public void onInitialize() {
        System.out.println("[mcdp-smoke] " + StringUtils.center(" mcdp-1.21.11 boot ok ", 60, "="));
        System.out.println("[mcdp-smoke] mod=fabric_example_12111 boot ok");
    }
}

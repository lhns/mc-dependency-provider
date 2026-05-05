package com.example.fabric1206;

import net.fabricmc.api.ModInitializer;
import org.apache.commons.lang3.StringUtils;

public final class FabricExampleMod implements ModInitializer {
    @Override
    public void onInitialize() {
        System.out.println("[mcdp-smoke] " + StringUtils.center(" mcdp-1.20.6 boot ok ", 60, "="));
        System.out.println("[mcdp-smoke] mod=fabric_example_1206 boot ok");
    }
}

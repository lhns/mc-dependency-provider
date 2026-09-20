package com.example.fabric263;

import net.fabricmc.api.ModInitializer;
import org.apache.commons.lang3.StringUtils;

public final class FabricExampleMod implements ModInitializer {
    @Override
    public void onInitialize() {
        System.out.println("[mcdp-smoke] " + StringUtils.center(" mcdp-26 boot ok ", 60, "="));
        System.out.println("[mcdp-smoke] mod=fabric_example_263 boot ok");
    }
}

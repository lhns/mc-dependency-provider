package com.example.neoforge1206;

import net.neoforged.fml.common.Mod;
import org.apache.commons.lang3.StringUtils;

@Mod("neoforge_example_1206")
public final class NeoForgeExampleMod {
    public NeoForgeExampleMod() {
        System.out.println("[mcdp-smoke] " + StringUtils.center(" mcdp-1.20.6 boot ok ", 60, "="));
        System.out.println("[mcdp-smoke] mod=neoforge_example_1206 boot ok");
    }
}

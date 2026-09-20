package com.example.neoforge12111;

import net.neoforged.fml.common.Mod;
import org.apache.commons.lang3.StringUtils;

@Mod("neoforge_example_12111")
public final class NeoForgeExampleMod {
    public NeoForgeExampleMod() {
        System.out.println("[mcdp-smoke] " + StringUtils.center(" mcdp-1.21.11 boot ok ", 60, "="));
        System.out.println("[mcdp-smoke] mod=neoforge_example_12111 boot ok");
    }
}

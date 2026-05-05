package com.example.neoforge261;

import net.neoforged.fml.common.Mod;
import org.apache.commons.lang3.StringUtils;

@Mod("neoforge_example_261")
public final class NeoForgeExampleMod {
    public NeoForgeExampleMod() {
        System.out.println("[mcdp-smoke] " + StringUtils.center(" mcdp-26.1 boot ok ", 60, "="));
        System.out.println("[mcdp-smoke] mod=neoforge_example_261 boot ok");
    }
}

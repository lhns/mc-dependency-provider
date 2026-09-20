package com.example.neoforge262;

import net.neoforged.fml.common.Mod;
import org.apache.commons.lang3.StringUtils;

@Mod("neoforge_example_262")
public final class NeoForgeExampleMod {
    public NeoForgeExampleMod() {
        System.out.println("[mcdp-smoke] " + StringUtils.center(" mcdp-26 boot ok ", 60, "="));
        System.out.println("[mcdp-smoke] mod=neoforge_example_262 boot ok");
    }
}

package com.example.forge120;

import net.minecraftforge.fml.common.Mod;
import org.apache.commons.lang3.StringUtils;

@Mod("forge_example_120")
public final class ForgeExampleMod {
    public ForgeExampleMod() {
        System.out.println("[mcdp-smoke] " + StringUtils.center(" mcdp-forge-1.20 boot ok ", 60, "="));
        System.out.println("[mcdp-smoke] mod=forge_example_120 boot ok");
    }
}

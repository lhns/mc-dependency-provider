package com.example.forge119;

import net.minecraftforge.fml.common.Mod;
import org.apache.commons.lang3.StringUtils;

@Mod("forge_example_119")
public final class ForgeExampleMod {
    public ForgeExampleMod() {
        System.out.println("[mcdp-smoke] " + StringUtils.center(" mcdp-forge-1.19 boot ok ", 60, "="));
        System.out.println("[mcdp-smoke] mod=forge_example_119 boot ok");
    }
}

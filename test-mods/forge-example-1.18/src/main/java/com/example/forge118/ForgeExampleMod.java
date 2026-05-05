package com.example.forge118;

import net.minecraftforge.fml.common.Mod;
import org.apache.commons.lang3.StringUtils;

/**
 * Minimal Forge 1.18.2 mod loaded by mcdepprovider. Verifies:
 * <ol>
 *   <li>The Forge IModLanguageProvider gets reached by FML.</li>
 *   <li>The mcdp manifest at META-INF/mcdepprovider.toml is parsed and libraries are
 *       resolved (commons-lang3 here).</li>
 *   <li>The per-mod ModClassLoader actually loads commons-lang3 — calling
 *       {@code StringUtils.center} requires the lib to be reachable, otherwise
 *       NoClassDefFoundError surfaces during entry construction.</li>
 *   <li>The smoke marker on stdout proves the entry constructor ran.</li>
 * </ol>
 */
@Mod("forge_example_118")
public final class ForgeExampleMod {
    public ForgeExampleMod() {
        // Calls into commons-lang3 — fails fast at instantiation if mcdp didn't expose
        // the library to this mod's classloader.
        String banner = StringUtils.center(" mcdp-forge-1.18 boot ok ", 60, "=");
        System.out.println("[mcdp-smoke] " + banner);
        System.out.println("[mcdp-smoke] mod=forge_example_118 boot ok");
    }
}

package com.example.forge120;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.apache.commons.lang3.StringUtils;

/**
 * Takes the full ADR-0017 context bag {@code (IEventBus, ModContainer, Dist)}. A non-null
 * {@code Dist} proves {@code Environment.get()} resolved; reaching the constructor at all proves
 * the CONSTRUCT staging in {@code McdpModContainer.activityMap} fired, since FML never calls it
 * during {@code loadMod}.
 */
@Mod("forge_example_120")
public final class ForgeExampleMod {

    public ForgeExampleMod(IEventBus modBus, ModContainer container, Dist dist) {
        SmokeLog.emit(StringUtils.center(" mcdp-forge-1.20 boot ok ", 60, "="));
        SmokeLog.emit("mod=" + container.getModId() + " boot ok");
        SmokeLog.emit("stage=CONSTRUCT mod=" + container.getModId() + " dist=" + dist);

        // The only thing that can prove McdpModContainer.acceptEvent actually delivers: vanilla
        // ModContainer.acceptEvent is a no-op, so without the override this never fires.
        modBus.addListener(this::onCommonSetup);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        SmokeLog.emit("stage=FMLCommonSetupEvent mod=forge_example_120");
    }
}

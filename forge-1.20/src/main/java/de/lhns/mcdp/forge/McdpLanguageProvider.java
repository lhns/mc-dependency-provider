package de.lhns.mcdp.forge;

import net.minecraftforge.forgespi.language.ILifecycleEvent;
import net.minecraftforge.forgespi.language.IModLanguageProvider;
import net.minecraftforge.forgespi.language.ModFileScanData;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Forge {@link IModLanguageProvider} for mcdp on MC 1.20.x (forgespi 7.x).
 *
 * <p><b>STATUS: STUB.</b> Forge 7.x diverges from 4.0.x in the {@code locating} package
 * (ForgeFeature, ModFileFactory) and the supporting {@code IModInfo}/{@code IModFileInfo}
 * surfaces, plus the {@code IModLanguageLoader} loadMod signature subtly differs. Direct
 * source share with {@code forge-1.18/} doesn't compile.
 *
 * <p>Implementing 1.20 is a separate focused effort. The 1.18 adapter is a structural
 * reference; once 1.18 is runtime-verified against a test mod, we copy + tweak the source
 * for 7.x SPI shifts.</p>
 */
public final class McdpLanguageProvider implements IModLanguageProvider {

    public static final String LANGUAGE_ID = "mcdepprovider";

    @Override
    public String name() {
        return LANGUAGE_ID;
    }

    @Override
    public Consumer<ModFileScanData> getFileVisitor() {
        throw new UnsupportedOperationException(
                "mcdp Forge 1.20 adapter not yet implemented. forge-1.18 is the reference;"
                        + " 1.20's forgespi 7.x has API drift that requires per-band source.");
    }

    @Override
    public <R extends ILifecycleEvent<R>> void consumeLifecycleEvent(Supplier<R> consumeEvent) {
    }
}

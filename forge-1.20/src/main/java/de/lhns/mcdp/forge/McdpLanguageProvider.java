package de.lhns.mcdp.forge;

import net.minecraftforge.forgespi.language.ILifecycleEvent;
import net.minecraftforge.forgespi.language.IModLanguageProvider;
import net.minecraftforge.forgespi.language.ModFileScanData;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Forge {@link IModLanguageProvider} for mcdp on MC 1.20.x (forgespi 7.x).
 *
 * <p><b>STATUS: SCAFFOLD ONLY.</b> See {@code forge-1.18/.../McdpLanguageProvider} for the
 * full Javadoc roadmap. The 7.x SPI's {@code IModLanguageProvider} top-level is identical
 * to 4.0.x; the divergence is in {@code locating} (ForgeFeature, ModFileFactory) and the
 * supporting {@code IModInfo}/{@code IModFileInfo} surfaces — relevant when filling in
 * the discovery side, not for this scaffold.</p>
 */
public final class McdpLanguageProvider implements IModLanguageProvider {

    private static final String NAME = "mcdepprovider";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Consumer<ModFileScanData> getFileVisitor() {
        throw new UnsupportedOperationException(
                "mcdp Forge 1.20 adapter scaffold: getFileVisitor not yet implemented.");
    }

    @Override
    public <R extends ILifecycleEvent<R>> void consumeLifecycleEvent(Supplier<R> consumeEvent) {
    }
}

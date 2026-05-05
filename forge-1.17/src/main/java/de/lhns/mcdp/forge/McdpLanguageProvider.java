package de.lhns.mcdp.forge;

import net.minecraftforge.forgespi.language.ILifecycleEvent;
import net.minecraftforge.forgespi.language.IModLanguageProvider;
import net.minecraftforge.forgespi.language.ModFileScanData;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Forge {@link IModLanguageProvider} for mcdp on MC 1.17.x (forgespi 3.2.x).
 *
 * <p><b>STATUS: SCAFFOLD ONLY.</b> See the equivalent class in {@code forge-1.18/} for the
 * full Javadoc roadmap. The 3.2.x SPI is structurally identical to 4.0.x for the surface
 * mcdp uses; the version-specific divergence is mostly in {@code IModLanguageLoader.loadMod}'s
 * {@code ModuleLayer} parameter (Java 16 introduces JPMS module layers; the loadMod signature
 * is the same shape between 3.2.x and 4.0.x).</p>
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
                "mcdp Forge 1.17 adapter scaffold: getFileVisitor not yet implemented. "
                        + "See forge-1.18/.../McdpLanguageProvider for the full roadmap.");
    }

    @Override
    public <R extends ILifecycleEvent<R>> void consumeLifecycleEvent(Supplier<R> consumeEvent) {
    }
}

package de.lhns.mcdp.forge;

import net.minecraftforge.forgespi.language.ILifecycleEvent;
import net.minecraftforge.forgespi.language.IModLanguageProvider;
import net.minecraftforge.forgespi.language.ModFileScanData;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Forge {@link IModLanguageProvider} for mcdp on MC 1.17.x (forgespi 3.2.x).
 *
 * <p><b>STATUS: STUB.</b> The 3.2.x SPI is shaped differently from 4.0.x/7.x in ways
 * that prevent direct source sharing with {@code forge-1.18/}: most notably, 3.2's
 * {@code IModFileInfo} has no {@code getFile()} method, so navigating from
 * {@code IModInfo.getOwningFile()} back to {@code IModFile.findResource(...)} (which is
 * how the 1.18 adapter reads the manifest) needs a different traversal — likely via
 * {@code ModFileScanData.getIModInfoData()} cross-referenced with the file scan results.
 *
 * <p>Implementing 1.17 is a separate focused effort. For now this stub returns the
 * correct {@code name()} so FML's service-load doesn't fail; {@code getFileVisitor}
 * throws — meaning a mod declaring {@code modLoader = "mcdepprovider"} on 1.17 will
 * fail fast with a clear message rather than booting halfway and crashing later.</p>
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
                "mcdp Forge 1.17 adapter not yet implemented. forge-1.18 is the reference;"
                        + " 1.17's IModFileInfo lacks getFile() so the manifest-discovery path"
                        + " differs.");
    }

    @Override
    public <R extends ILifecycleEvent<R>> void consumeLifecycleEvent(Supplier<R> consumeEvent) {
    }
}

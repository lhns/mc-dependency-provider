package de.lhns.mcdp.forge;

import net.minecraftforge.forgespi.language.ILifecycleEvent;
import net.minecraftforge.forgespi.language.IModLanguageProvider;
import net.minecraftforge.forgespi.language.ModFileScanData;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Forge {@link IModLanguageProvider} for mcdp on MC 1.18.x (forgespi 4.0.x). Discovered by
 * Forge via {@code META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider}.
 *
 * <p><b>STATUS: SCAFFOLD ONLY.</b> This class compiles against the SPI and registers
 * correctly with Forge, but the body of {@link #getFileVisitor()} is not yet implemented —
 * calling it throws {@link UnsupportedOperationException}. The scaffold exists so 1.17 and
 * 1.20 forge bands can clone the build.gradle.kts wiring, and so the libs.versions.toml
 * entry for {@code forge-spi-1-18} is exercised in CI's compileJava step.
 *
 * <p>The full implementation needs to:
 * <ol>
 *   <li>Walk {@link ModFileScanData} for the mod's {@code @Mod}-annotated entry class +
 *       the mcdp manifest at {@code META-INF/mcdepprovider.toml}.</li>
 *   <li>Download the manifest's libraries via {@code deps-lib}'s {@code ManifestConsumer}.</li>
 *   <li>Build a per-mod {@code ModClassLoader} and register it with {@code McdpProvider}.</li>
 *   <li>Implement an {@code IModLanguageLoader} whose {@code loadMod} constructs a
 *       {@code ModContainer} subclass (Forge 1.18 specific — likely
 *       {@code FMLModContainer}-style with a custom mod-loading dispatch).</li>
 *   <li>Wire {@code @EventBusSubscriber} via Forge's {@code AutomaticEventSubscriber.inject}.</li>
 * </ol>
 *
 * <p>The runtime model carries over from {@code McdpLanguageLoader} (NeoForge): manifest
 * read → library resolve → ModClassLoader build → entry instantiate. Forge 1.18 still uses
 * the JDK module system for {@code ModFileScanData.getTargetCollection()} so JPMS package
 * validation hits cats-kernel here too — same problem mcdp solves on NeoForge.
 *
 * <p>See ADR-0001 (per-mod URLClassLoader), ADR-0008 (Mixin bridge pattern), ADR-0023 (TBD —
 * multi-band publication) for the architectural context.
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
                "mcdp Forge 1.18 adapter scaffold: getFileVisitor not yet implemented. "
                        + "Fill in mod discovery + ModClassLoader build per the class Javadoc.");
    }

    @Override
    public <R extends ILifecycleEvent<R>> void consumeLifecycleEvent(Supplier<R> consumeEvent) {
        // No-op — mcdp doesn't currently hook into Forge's per-mod lifecycle phases.
    }
}

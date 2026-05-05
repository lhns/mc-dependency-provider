package de.lhns.mcdp.forge;

import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.forgespi.language.IModInfo;

/**
 * Forge 1.18 {@code ModContainer} subclass for mcdp-loaded mods. Constructed by
 * {@link McdpLanguageProvider.McdpModLanguageLoader#loadMod} once per mod that declares
 * {@code modLoader = "mcdepprovider"} in its {@code mods.toml}.
 *
 * <p><b>STATUS: PARTIAL.</b> Compiles against {@code net.minecraftforge:fmlcore:1.18.2-40.x}
 * and matches the abstract surface of {@code net.minecraftforge.fml.ModContainer}. The
 * {@link #getMod()} return is a placeholder — the next step is to read the mod's
 * {@code META-INF/mcdepprovider.toml}, download libraries via {@code deps-lib}'s
 * {@code ManifestConsumer}, build a {@code de.lhns.mcdp.core.ModClassLoader}, and
 * instantiate the {@code @Mod}-annotated entry class through it.
 *
 * <p>Roadmap for the remaining work (cribs from {@code McdpLanguageLoader} on the NeoForge
 * 21.x branch — same pattern, different SPIs):
 * <ol>
 *   <li>In the constructor, locate {@code META-INF/mcdepprovider.toml} via
 *       {@code modInfo.getOwningFile().getFile().findResource(...)}; fail loud if missing.</li>
 *   <li>Parse with {@code de.lhns.mcdp.deps.ManifestIo}.</li>
 *   <li>Resolve libraries via a static {@code ManifestConsumer} (one per JVM, mirrored from
 *       NeoForge's {@code McdpLanguageLoader#CONSUMER}).</li>
 *   <li>Build the {@code ModClassLoader} via {@code LoaderCoordinator.register(modId,
 *       manifest, modPaths, libs, libParent)} and stash the result.</li>
 *   <li>Load the entry class through the per-mod loader, instantiate via the appropriate
 *       {@code EntrypointAdapter} (Java/Scala/Kotlin per the manifest's {@code lang}).</li>
 *   <li>Wire {@code @EventBusSubscriber} via Forge's {@code AutomaticEventSubscriber.inject}
 *       (located at {@code net.minecraftforge.fml.javafmlmod.AutomaticEventSubscriber} in
 *       Forge 1.18, with a different signature than NeoForge's).</li>
 * </ol>
 *
 * <p>Forge's lifecycle (ModLoadingStage transitions, IExtensionPoint, IModBusEvent dispatch)
 * is also wired here when ready. The simplest first cut keeps the mod object opaque to FML
 * — register it with {@link #setMod(Object)} or similar — and lets FML drive the standard
 * {@code @SubscribeEvent}-based event delivery, which works as long as the mod's classes
 * are reachable from the per-mod ModClassLoader.</p>
 */
public final class McdpModContainer extends ModContainer {

    private final String entryFqn;
    private Object mod;

    McdpModContainer(IModInfo info, String entryFqn) {
        super(info);
        this.entryFqn = entryFqn;
        this.mod = new Object();   // placeholder until the entry instantiation lands
    }

    @Override
    public boolean matches(Object mod) {
        return mod == this.mod;
    }

    @Override
    public Object getMod() {
        return mod;
    }

    /**
     * Stash the entry instance once the construct step lands. Public so a future
     * register-the-mod hook can install the real entry without exposing the field.
     */
    public void setMod(Object newMod) {
        this.mod = newMod;
    }

    public String getEntryFqn() {
        return entryFqn;
    }
}

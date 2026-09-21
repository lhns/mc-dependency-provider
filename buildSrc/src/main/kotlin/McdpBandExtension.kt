import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Property

/**
 * The per-band knobs of the shared shadow/publish recipe.
 *
 * Everything else about a band module (its repositories, its SPI pins, whether it shares a
 * source tree with a sibling band) stays in that module's own build.gradle.kts, because it
 * is genuinely band-specific rather than boilerplate.
 */
abstract class McdpBandExtension {
    /**
     * Bytecode target for this band, i.e. the Java version the band's Minecraft JVM ships.
     * Required — there is no sensible default, and on Fabric bands it also becomes the
     * `depends.java` floor in `fabric.mod.json`, where a wrong guess is a runtime failure.
     */
    abstract val javaRelease: Property<Int>

    /**
     * The band's `depends.fabricloader` floor, filled into the shared `fabric.mod.json`
     * template. Fabric adapter modules only; leaving it unset is what tells
     * `mcdp.band-adapter` this module has no Fabric metadata to expand.
     */
    abstract val fabricLoaderVersion: Property<String>

    /**
     * The fabric-loader artifact this band *compiles* against, as opposed to the runtime floor
     * above. `mcdp.fabric-band` defaults it to the catalog's `fabric-loader` pin; a band sets it
     * only when it needs a different loader line (fabric-26). Fabric adapter modules only.
     */
    abstract val fabricLoaderArtifact: Property<MinimalExternalModuleDependency>

    /**
     * FML's jar-type manifest attribute, or unset for jars FML never sees (the Fabric-only
     * adapters). `LANGPROVIDER` on Forge-bundling bands (≤ 1.20.4);
     * `LIBRARY` on NeoForge bands, where FML 4.x/8.x+ routes LIBRARY jars into the PLUGIN
     * module layer it ServiceLoader-scans for IModLanguageLoader and `LANGPROVIDER` is not
     * a valid value.
     */
    abstract val fmlModType: Property<String>

    /** POM `<description>`. Aggregator modules only. */
    abstract val pomDescription: Property<String>
}

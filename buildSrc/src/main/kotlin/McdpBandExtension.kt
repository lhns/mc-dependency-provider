import org.gradle.api.provider.Property

/**
 * The per-band knobs of the shared shadow/publish recipe.
 *
 * Everything else about a band module (its repositories, its SPI pins, whether it shares a
 * source tree with a sibling band) stays in that module's own build.gradle.kts, because it
 * is genuinely band-specific rather than boilerplate.
 */
abstract class McdpBandExtension {
    /** Bytecode target for this band, i.e. the Java version the band's Minecraft JVM ships. */
    abstract val javaRelease: Property<Int>

    /**
     * Base name of the shaded jar. Defaults to `mcdp-<project name>` for adapter modules and
     * to `<project name>` for band aggregators (whose project name already is the published
     * artifactId — see settings.gradle.kts).
     */
    abstract val archiveBaseName: Property<String>

    /**
     * FML's jar-type manifest attribute, or unset for jars FML never sees (the Fabric-only
     * adapters). `LANGPROVIDER` on Forge-bundling bands (≤ 1.20.4); `LIBRARY` on NeoForge
     * bands, where FML 4.x/8.x+ routes LIBRARY jars into the PLUGIN module layer it
     * ServiceLoader-scans for IModLanguageLoader and `LANGPROVIDER` is not a valid value.
     */
    abstract val fmlModType: Property<String>

    /** POM `<name>`; defaults to the project name. Aggregator modules only. */
    abstract val pomName: Property<String>

    /** POM `<description>`. Aggregator modules only. */
    abstract val pomDescription: Property<String>
}

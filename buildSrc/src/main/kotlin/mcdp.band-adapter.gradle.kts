import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("mcdp.shaded-jar")
}

val mcdpBand = the<McdpBandExtension>()

// Adapter modules are named `<loader>-<band>` and publish nothing themselves; their shaded
// jar is consumed by the band aggregator (`:mcdp-<band>`) via apiElements/runtimeElements.
tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("mcdp-${project.name}")
}

// All six Fabric adapters share one `fabric.mod.json` (in `fabric/src/main/resources`,
// alongside the Java source they already share); the only per-band values are the two
// `depends` floors and the version, which are filled in here.
//
// The version matters most: Fabric's loader cannot parse a literal `${version}` as semver,
// falls back to a StringVersion, and then no consumer's `depends: { mcdepprovider: ">=0.1" }`
// can ever be satisfied.
//
// `expand()` runs Groovy's SimpleTemplateEngine over the file, which chokes on any stray
// `$`, so it is scoped with `filesMatching` to the one resource that is a template — every
// other resource in these source sets is a plain `META-INF/services/` entry. The map is a
// Provider so nothing is read before the band's `mcdpBand { }` block has run; it is absent
// on the Forge/NeoForge adapters, which set no `fabricLoaderVersion` and ship no
// `fabric.mod.json`, and a Fabric band that forgot to set one fails loudly here rather than
// shipping a broken floor.
val modMetadata = mcdpBand.fabricLoaderVersion.zip(mcdpBand.javaRelease) { fabricLoader, javaRelease ->
    mapOf(
        "version" to project.version.toString(),
        "fabricLoader" to fabricLoader,
        "javaRelease" to javaRelease.toString()
    )
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("mcdpModMetadata", modMetadata).optional(true)
    filesMatching("fabric.mod.json") {
        expand(modMetadata.get())
    }
}

// Every loader adapter shades the same two in-tree libraries; neither is published on its
// own (ADR-0016), so their classes have to ship inside the adapter jar.
dependencies {
    "bundle"(project(":core"))
    "bundle"(project(":deps-lib"))
}

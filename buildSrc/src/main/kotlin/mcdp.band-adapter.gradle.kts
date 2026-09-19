plugins {
    id("mcdp.shaded-jar")
}

// Adapter modules are named `<loader>-<band>` and publish nothing themselves; their shaded
// jar is consumed by the band aggregator (`:mcdp-<band>`) via apiElements/runtimeElements.
the<McdpBandExtension>().archiveBaseName.convention("mcdp-${project.name}")

// Every loader adapter shades the same two in-tree libraries; neither is published on its
// own (ADR-0016), so their classes have to ship inside the adapter jar.
dependencies {
    "bundle"(project(":core"))
    "bundle"(project(":deps-lib"))
}

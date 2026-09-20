plugins {
    `kotlin-dsl`
}

// The convention plugins in src/main/kotlin apply these two plugins, so their marker
// coordinates have to be on buildSrc's own compile/runtime classpath. Versions come from
// the shared catalog; `<id>:<id>.gradle.plugin:<version>` is the plugin-marker coordinate
// that `plugins { id(...) }` would resolve. `requiredVersion`, not `version.toString()`:
// the latter renders a rich version (`strictly`, `prefer`, a range) into something that is
// not a resolvable coordinate.
dependencies {
    implementation(libs.plugins.shadow.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}" })
    implementation(libs.plugins.vanniktech.maven.publish.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}" })
}

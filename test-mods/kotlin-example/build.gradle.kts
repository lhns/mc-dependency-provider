plugins {
    `java-library`
    kotlin("jvm") version "2.1.0"
    id("io.github.mclibprovider")
}

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // Representative Kotlin-ecosystem deps: the stdlib is bundled as a transitive of the Kotlin
    // plugin, kotlinx-coroutines-core is the canonical "I want a Kotlin library" dependency, and
    // it exercises the manifest-generation path for a JVM Kotlin mod.
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

mclibprovider {
    lang.set("kotlin")
    sharedPackages.add("com.example.api")
    // Platforms provide these at runtime — never bundle them.
    excludeGroup("net.minecraft")
    excludeGroup("net.neoforged")
    excludeGroup("net.fabricmc")
    excludeGroup("com.mojang")
}

plugins {
    `java-library`
    scala
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
    // A realistic Scala-ecosystem dependency set — the motivating case for mc-lib-provider.
    // These are exactly the libraries that break under JPMS in a shared classpath
    // (cats-kernel has keyword package names) and they're what the architecture is designed
    // to resolve and serve through a per-mod URLClassLoader.
    implementation("org.scala-lang:scala3-library_3:3.5.2")
    implementation("org.typelevel:cats-core_3:2.13.0")
    implementation("io.circe:circe-core_3:0.14.10")
    implementation("io.circe:circe-parser_3:0.14.10")
}

mclibprovider {
    lang.set("scala")
    sharedPackages.add("com.example.api")
    // Platforms provide these at runtime — never bundle them.
    excludeGroup("net.minecraft")
    excludeGroup("net.neoforged")
    excludeGroup("net.fabricmc")
    excludeGroup("com.mojang")
}

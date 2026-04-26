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
    // mcLibImplementation is the opt-in bucket: deps placed here are emitted into
    // META-INF/mclibprovider.toml and served at runtime through a per-mod URLClassLoader.
    // These are exactly the libraries that break under JPMS in a shared classpath
    // (cats-kernel has keyword package names).
    mcLibImplementation("org.scala-lang:scala3-library_3:3.5.2")
    mcLibImplementation("org.typelevel:cats-core_3:2.13.0")
    mcLibImplementation("io.circe:circe-core_3:0.14.10")
    mcLibImplementation("io.circe:circe-parser_3:0.14.10")
}

mclibprovider {
    lang.set("scala")
    sharedPackages.add("com.example.api")
}

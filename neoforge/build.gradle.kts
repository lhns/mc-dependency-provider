plugins {
    `java-library`
}

repositories {
    maven("https://maven.neoforged.net/releases")
    // Mojang's repo — fancymodloader:loader pulls transitive com.mojang:logging from here.
    maven("https://libraries.minecraft.net/")
}

dependencies {
    api(project(":core"))
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.neoforge.spi)
    // NeoForge's FML classes are bundled into the runtime — compileOnly here so the provider jar
    // stays tiny. The McLibModContainer wrapper needs net.neoforged.fml.ModContainer (from the fml
    // loader) and net.neoforged.bus.api.IEventBus (from the bus artifact). Versions track what's
    // bundled in NeoForge 21.1.x.
    compileOnly(libs.neoforge.fml.loader)
    compileOnly(libs.neoforge.bus)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Tests instantiate the wrapper, so the NeoForge classes need to be on the test runtime
    // classpath (not just compile). Production always has them at runtime.
    testImplementation(libs.neoforge.spi)
    testImplementation(libs.neoforge.fml.loader)
    testImplementation(libs.neoforge.bus)
}

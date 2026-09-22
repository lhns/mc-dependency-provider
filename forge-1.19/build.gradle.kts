plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.minecraftforge.net/")
    // Test runtime only: fmlcore's runtime dependency fmlloader (1.19+) depends on Mojang's
    // com.mojang:logging, which LoadingModList's static initializer uses. Scoped to that group.
    maven("https://libraries.minecraft.net/") {
        content { includeGroup("com.mojang") }
    }
}

// Forge for MC 1.19.x (1.19.2-43.5.2). Adapter source is shared with the 1.18 band, exactly as
// forge-1.17 and forge-1.20 already do: forgespi 6.0.x sits between the 4.0.x (1.17/1.18) and
// 7.x (1.20) surfaces the shared source already compiles against, and every type it touches is
// identical on all three. This file only pins the 1.19 coordinates (ADR-0031).
mcdpBand {
    javaRelease.set(17)
}

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("forge-1.18/src/main/java")))
        resources.setSrcDirs(listOf("src/main/resources"))
    }
    test {
        java.setSrcDirs(listOf(rootProject.file("forge-1.18/src/test/java")))
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // Forge SPI 6.0.x — the whole 1.19 line; the catalog explains why the pin is exact.
    compileOnly(libs.forge.spi.mc119)
    // fmlcore for Forge 1.19.2 — supplies net.minecraftforge.fml.ModContainer base class
    // and lifecycle types (ModLoadingStage, IExtensionPoint, IModBusEvent).
    compileOnly(libs.forge.fmlcore.mc119)
    // EventBus + ASM (TypeRef in ModFileScanData) — transitives of fmlcore at runtime, needed
    // explicitly at compile time; the catalog documents which eventbus line each band takes.
    compileOnly(libs.forge.eventbus.mc119)
    compileOnly(libs.asm)
    compileOnly(libs.maven.artifact)

    // The shared unit tests (forge-1.18/src/test/java) run on this band's own coordinates; see
    // forge-1.18/build.gradle.kts for why each of these is needed at test runtime.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":core"))
    testImplementation(project(":deps-lib"))
    testImplementation(libs.forge.spi.mc119)
    testImplementation(libs.forge.fmlcore.mc119)
    testImplementation(libs.forge.eventbus.mc119)
    testImplementation(libs.asm)
    testImplementation(libs.maven.artifact)
}

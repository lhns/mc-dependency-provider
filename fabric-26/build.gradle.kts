plugins {
    id("mcdp.band-adapter")
}

repositories {
    maven("https://maven.fabricmc.net/")
}

// Fabric for Mojang's calendar-versioning line (MC 26.1, 26.2, 26.3, …) — ONE band for the
// whole 26.x line, not one per release. See ADR-0032: the Fabric SPI this adapter touches
// (`net.fabricmc.loader.api.LanguageAdapter`, `…api.entrypoint.PreLaunchEntrypoint`,
// `…api.ModContainer`) is *byte-identical* between fabric-loader 0.16.9 (the 1.21 pin) and
// 0.19.5 (the 26.3 line) — same SHA-1, class-file major 52. So this band shares fabric/'s
// source exactly like every other Fabric band and differs only in its pins and floors.
mcdpBand {
    // 21, not 25. MC 26.x ships a Java 25 runtime, but this is the *bytecode target* and
    // JVM bytecode is forward-compatible: a release-21 class loads fine on a JVM 25. Raising
    // it to 25 would require a JDK 25 toolchain for the whole root build, which we do not
    // have (Gradle 8.11.1 / JDK 21). The `depends.java >= 21` floor it also feeds into
    // fabric.mod.json is a floor, so it is satisfied — not violated — on a 26.x runtime.
    javaRelease.set(21)
    // 0.19 is the fabric-loader line contemporaneous with MC 26.3 and the line that carries
    // the calendar-version game provider. A floor that is too LOW is a silent runtime
    // failure; one that is too high only asks a consumer to update their loader.
    fabricLoaderVersion.set("0.19")
}

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("fabric/src/main/java")))
        resources.setSrcDirs(listOf(rootProject.file("fabric/src/main/resources")))
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":deps-lib"))
    compileOnly(libs.jetbrains.annotations)
    // Compiled against the 26.x-era loader rather than the 1.21 pin. Safe on the JDK 21
    // toolchain: fabric-loader's api classes are still major 52 (Java 8) even at 0.19.5.
    compileOnly(libs.fabric.loader.mc26)
    compileOnly("org.slf4j:slf4j-api:2.0.9")
}

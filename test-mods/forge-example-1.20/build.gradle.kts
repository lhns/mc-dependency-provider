// ForgeGradle 6.x supports MC 1.20.1 + Gradle 8 (the 1.18-era 5.1.x line is for older
// MC). This subproject can run on the parent's Gradle 8.x — no wrapper override needed.
buildscript {
    repositories {
        maven("https://maven.minecraftforge.net/")
        mavenCentral()
    }
    dependencies {
        classpath("net.minecraftforge.gradle:ForgeGradle:6.0.+") { isChanging = true }
    }
}

plugins {
    `java-library`
    id("de.lhns.mcdp")
}

apply(plugin = "net.minecraftforge.gradle")

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories { mavenCentral() }

configure<net.minecraftforge.gradle.userdev.UserDevExtension> {
    mappings("official", "1.20.1")
    runs {
        // Tier-3 runClient cell (.github/workflows/mc-client-nightly.yml).
        //   * `--mixin.config` is repeated from `server` on purpose: BlocksClinitMixin sits in
        //     the side-neutral "mixins" array of forge_example_120.mixins.json, so it applies
        //     on a client boot too and is the one mixin marker a client cell can assert.
        //   * `fml.earlyprogresswindow=false` — FML opens a second GLFW window before the game
        //     window; on the software rasterizer CI runs under that is the most common cause of
        //     a dev client that never reaches the title screen.
        create("client") {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "info")
            property("fml.earlyprogresswindow", "false")
            args("--mixin.config", "forge_example_120.mixins.json")
            mods {
                create("forge_example_120") { source(sourceSets.main.get()) }
            }
        }
        create("server") {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "info")
            // Forge 1.20.1 has no mixin integration of its own and never reads a [[mixins]]
            // block out of mods.toml — registration is Mixin's own ModLauncher option, which
            // is also exactly what MixinGradle emits for userdev runs. Dev-only; the jar
            // manifest attribute below covers a production launch.
            args("--mixin.config", "forge_example_120.mixins.json")
            mods {
                create("forge_example_120") { source(sourceSets.main.get()) }
            }
        }
    }
}

// Production registration path (Mixin's MixinPlatformAgentDefault reads this attribute off the
// container manifest). Unused by the dev runServer, which finds the config via --mixin.config.
tasks.jar {
    manifest { attributes("MixinConfigs" to "forge_example_120.mixins.json") }
}

dependencies {
    // Brings org.spongepowered:mixin:0.8.5 transitively at compile scope — hence no explicit
    // mixin dependency here. 0.8.5's highest compatibilityLevel is JAVA_17.
    "minecraft"("net.minecraftforge:forge:1.20.1-47.4.20")
    implementation("de.lhns.mcdp:mcdp-1.20:0.2.1-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

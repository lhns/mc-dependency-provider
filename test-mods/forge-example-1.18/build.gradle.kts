// MC 1.18.2 on ForgeGradle 6, the shape of the official `forge-1.18.2-40.3.12-mdk.zip`:
// that MDK declares `id 'net.minecraftforge.gradle' version '[6.0,6.2)'` and ships a Gradle
// 8.8 wrapper. FG 6.0.54's EnvironmentChecks accepts Gradle [8.1, 9.0), so this mod runs on
// the repo-root 8.11.1 wrapper through `includeBuild("../..")` like every other test mod.
//
// The older FG 5.1.x / Gradle 7.6 scaffold that used to live here was based on the claim that
// FG 5.1 is the only ForgeGradle line for MC <= 1.18. It is not — Forge regenerated the MDKs
// for 1.16.5 and 1.18.2 onto FG6. (forge-example-1.17 is a separate question: its MDK was
// never regenerated and it has its own JDK-16 constraint.)
//
// FG6 is applied through the `plugins {}` block, resolved from the MinecraftForge maven listed
// in settings.gradle.kts's pluginManagement — the MDK's own form. The Kotlin-DSL surface still
// reaches the Groovy-typed extension via `configure<UserDevExtension>`.
plugins {
    `java-library`
    id("net.minecraftforge.gradle") version "[6.0,6.2)"
    id("de.lhns.mcdp")
}

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        // MC 1.18.2 requires Java 17. On a JDK-21 Gradle daemon this is a *toolchain*
        // requirement, which CI satisfies from JAVA_HOME_17_X64 (setup-java still installs 17).
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories { mavenCentral() }

configure<net.minecraftforge.gradle.userdev.UserDevExtension> {
    mappings("official", "1.18.2")
    runs {
        // Tier-3 runClient cell (.github/workflows/mc-client-nightly.yml). Same shape as
        // `server` plus `fml.earlyprogresswindow=false`: FML opens a second GLFW window
        // before the game window, and on the software rasterizer the CI client runs under
        // that is the most common cause of a boot that never reaches the title screen.
        create("client") {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "info")
            property("fml.earlyprogresswindow", "false")
            mods {
                create("forge_example_118") {
                    source(sourceSets.main.get())
                }
            }
        }
        create("server") {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "info")
            mods {
                create("forge_example_118") {
                    source(sourceSets.main.get())
                }
            }
        }
    }
}

dependencies {
    "minecraft"("net.minecraftforge:forge:1.18.2-40.3.12")

    // mcdpprovider runtime jar — composite-substituted to :mcdp-1.18 by mc-scala root.
    implementation("de.lhns.mcdp:mcdp-1.18:0.1.0-SNAPSHOT")

    // Mod's own Maven dep, served by mcdp at runtime.
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider {
    lang.set("java")
}

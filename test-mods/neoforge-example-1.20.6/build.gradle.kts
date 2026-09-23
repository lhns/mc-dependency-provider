plugins {
    `java-library`
    // ModDevGradle 2.0.x supports NeoForge 20.6.x.
    id("net.neoforged.moddev") version "2.0.141"
    id("de.lhns.mcdp")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.neoforged.net/releases/")
}

group = "com.example"
version = "0.1.0"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

neoForge {
    // NeoForge 20.6.x for MC 1.20.6 (latest patch in the 20.6 line).
    version = "20.6.139"

    runs {
        // `client` exists for the Tier-3 runClient nightly. Kept byte-for-byte in the shape
        // of test-mods/neoforge-example's — that is the only NeoForge client config CI has
        // ever booted, so it is the one to copy rather than improve on. (If this cell hangs
        // before the title screen, the first lever to pull is
        // `systemProperty("fml.earlyprogresswindow", "false")`, which is what the Forge
        // band mods set; it is left off here only to keep MDG's proven config untouched.)
        create("client") { client() }
        create("server") { server() }
    }

    mods {
        create("neoforge_example_1206") { sourceSet(sourceSets.main.get()) }
    }
}

dependencies {
    implementation("de.lhns.mcdp:mcdp-1.20.6:0.2.1-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") { expand("version" to project.version) }
}

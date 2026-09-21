plugins {
    `java-library`
    // MDG 2.0.147 is the newest published moddev-gradle (maven.neoforged.net, 2026-09-11) and
    // post-dates NeoForge 21.11.45 by nine months, so its variant schema knows the
    // `neoforge-dependencies` capability this NeoForge line advertises. ADR-0023's "MDG <->
    // NeoForge version pairing" finding is the reason this is pinned deliberately rather than
    // copied from the 1.20.6 mod (which sits on 2.0.141 to match NeoForge 20.6.139).
    id("net.neoforged.moddev") version "2.0.147"
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
    // Newest non-beta 21.11.x. Its POM declares net.neoforged.fancymodloader:loader:10.0.36,
    // which is what :neoforge-1.21.11 compiles against.
    version = "21.11.45"

    runs {
        // Tier-3 runClient cell; same shape as test-mods/neoforge-example's client config.
        create("client") { client() }
        create("server") { server() }
    }

    mods {
        create("neoforge_example_12111") { sourceSet(sourceSets.main.get()) }
    }
}

dependencies {
    implementation("de.lhns.mcdp:mcdp-1.21.11:0.1.0-SNAPSHOT")
    mcdepImplementation("org.apache.commons:commons-lang3:3.12.0")
}

mcdepprovider { lang.set("java") }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") { expand("version" to project.version) }
}

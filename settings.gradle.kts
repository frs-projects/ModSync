pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        maven("https://maven.architectury.dev/") { name = "Architectury" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.8"
}

rootProject.name = "ModSync"

// Pure-Java engine: no Minecraft, no mappings, no preprocessing. Deliberately
// outside the Stonecutter tree so it is compiled and tested exactly once.
include("core")

stonecutter {
    create(rootProject) {
        fun match(mc: String, vararg loaders: String) = loaders.forEach {
            version("$mc-$it", mc).buildscript = "build.$it.gradle.kts"
        }

        match("1.20.1", "fabric", "forge")
        match("1.21.1", "fabric", "neoforge")
        match("1.21.4", "fabric", "neoforge")
        match("1.21.8", "fabric", "neoforge")
        // 26.x is unobfuscated; Architectury Loom cannot set it up yet. See NOTES.
        // match("26.2", "fabric", "neoforge")

        vcsVersion = "1.21.1-neoforge"
    }
}

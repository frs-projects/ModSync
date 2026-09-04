plugins {
    id("dev.architectury.loom")
    id("modsync-platform")
}

loom {
    silentMojangMappingsLicense()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("deps.minecraft")}")
    // Minecraft ships unobfuscated from the 26.x line on, so Mojang no longer
    // publishes mapping files and there is nothing to remap against.
    if (property("deps.mappings") == "unobfuscated") {
        mappings(loom.layered { })
    } else {
        mappings(loom.officialMojangMappings())
    }
    "forge"("net.minecraftforge:forge:${property("deps.forge")}")
}

loom {
    runs {
        named("client") { runDir("run/client") }
        named("server") { runDir("run/server") }
    }
}

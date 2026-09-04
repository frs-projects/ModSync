plugins {
    id("dev.architectury.loom")
    id("modsync-platform")
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
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
}

loom {
    runs {
        named("client") { runDir("run/client") }
        named("server") { runDir("run/server") }
    }
}

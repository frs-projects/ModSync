//? if forge {
/*package net.frsprojects.modsync;

import net.frsprojects.modsync.command.ForgeCommands;
import net.frsprojects.modsync.server.ModSyncServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import java.util.List;

@Mod(ModSync.MOD_ID)
public final class ModSyncForge {
    public ModSyncForge() {
        ModSync.init();
        ForgeCommands.init();
        if (FMLEnvironment.dist.isClient()) {
            ModSyncForgeClient.init();
        } else {
            initDedicatedServer();
        }
    }

    // An integrated server shares the client's game directory, which the client syncs itself,
    // so only a dedicated server keeps its own mods in line. Listeners are method references
    // because Forge's eventbus cannot infer an event type from an untyped lambda.
    private static void initDedicatedServer() {
        ModSyncServer.init(
            FMLPaths.GAMEDIR.get(),
            "forge",
            ModList.get().getModContainerById("minecraft")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse(ModSync.MINECRAFT),
            () -> List.of(ModList.get().getModFileById(ModSync.MOD_ID).getFile().getFilePath()));
        MinecraftForge.EVENT_BUS.addListener(ModSyncForge::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(ModSyncForge::onServerStopped);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ModSyncServer.onServerStarted(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        ModSyncServer.onServerStopped(event.getServer());
    }
}
*///?}

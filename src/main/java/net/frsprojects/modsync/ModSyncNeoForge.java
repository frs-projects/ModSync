//? if neoforge {
package net.frsprojects.modsync;

import net.frsprojects.modsync.command.NeoForgeCommands;
import net.frsprojects.modsync.server.ModSyncServer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.List;

@Mod(ModSync.MOD_ID)
public final class ModSyncNeoForge {
    public ModSyncNeoForge() {
        ModSync.init();
        NeoForgeCommands.init();
        if (FMLEnvironment.dist.isClient()) {
            ModSyncNeoForgeClient.init();
        } else {
            initDedicatedServer();
        }
    }

    // An integrated server shares the client's game directory, which the client syncs itself,
    // so only a dedicated server keeps its own mods in line.
    private static void initDedicatedServer() {
        ModSyncServer.init(
            FMLPaths.GAMEDIR.get(),
            "neoforge",
            ModList.get().getModContainerById("minecraft")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse(ModSync.MINECRAFT),
            () -> List.of(ModList.get().getModFileById(ModSync.MOD_ID).getFile().getFilePath()));
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,
            e -> ModSyncServer.onServerStarted(e.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class,
            e -> ModSyncServer.onServerStopped(e.getServer()));
    }
}
//?}

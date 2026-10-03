//? if fabric {
/*package net.frsprojects.modsync;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.frsprojects.modsync.command.FabricCommands;
import net.frsprojects.modsync.server.ModSyncServer;

import java.util.List;

public final class ModSyncFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ModSync.init();
        FabricCommands.initServer();
        FabricLoader loader = FabricLoader.getInstance();
        // Dedicated servers only: an integrated server shares the client's game directory,
        // which the client syncs itself.
        if (loader.getEnvironmentType() == EnvType.SERVER) {
            ModSyncServer.init(
                loader.getGameDir(),
                "fabric",
                loader.getModContainer("minecraft")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse(ModSync.MINECRAFT),
                () -> loader.getModContainer(ModSync.MOD_ID)
                    .map(c -> c.getOrigin().getPaths())
                    .orElse(List.of()));
            ServerLifecycleEvents.SERVER_STARTED.register(ModSyncServer::onServerStarted);
            ServerLifecycleEvents.SERVER_STOPPED.register(ModSyncServer::onServerStopped);
        }
    }
}
*///?}

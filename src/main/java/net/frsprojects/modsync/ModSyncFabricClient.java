//? if fabric {
/*package net.frsprojects.modsync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.frsprojects.modsync.client.ModSyncClient;
import net.frsprojects.modsync.command.FabricCommands;

import java.util.List;

public final class ModSyncFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        ModSyncClient.init(
            loader.getGameDir(),
            "fabric",
            loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse(ModSync.MINECRAFT),
            // A jar in production; the dev run's output directories otherwise.
            () -> loader.getModContainer(ModSync.MOD_ID)
                .map(c -> c.getOrigin().getPaths())
                .orElse(List.of()));
        FabricCommands.initClient();
    }
}
*///?}

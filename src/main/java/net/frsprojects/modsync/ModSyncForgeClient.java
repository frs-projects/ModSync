//? if forge {
/*package net.frsprojects.modsync;

import net.frsprojects.modsync.client.ModSyncClient;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.util.List;

// The client half of ModSyncForge. Loaded only on a physical client, behind a dist check:
// ModSyncClient names client-only Minecraft classes that a dedicated server does not have.
public final class ModSyncForgeClient {

    private ModSyncForgeClient() {}

    public static void init() {
        ModSyncClient.init(
            FMLPaths.GAMEDIR.get(),
            "forge",
            ModList.get().getModContainerById("minecraft")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse(ModSync.MINECRAFT),
            () -> List.of(ModList.get().getModFileById(ModSync.MOD_ID).getFile().getFilePath()));
    }
}
*///?}

//? if neoforge {
package net.frsprojects.modsync;

import net.frsprojects.modsync.client.ModSyncClient;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.util.List;

// The client half of ModSyncNeoForge. Loaded only on a physical client, behind a dist check:
// ModSyncClient names client-only Minecraft classes that a dedicated server does not have.
public final class ModSyncNeoForgeClient {

    private ModSyncNeoForgeClient() {}

    public static void init() {
        ModSyncClient.init(
            FMLPaths.GAMEDIR.get(),
            "neoforge",
            ModList.get().getModContainerById("minecraft")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse(ModSync.MINECRAFT),
            () -> List.of(ModList.get().getModFileById(ModSync.MOD_ID).getFile().getFilePath()));
    }
}
//?}

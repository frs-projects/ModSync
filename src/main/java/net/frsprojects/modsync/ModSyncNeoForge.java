//? if neoforge {
package net.frsprojects.modsync;

import net.frsprojects.modsync.command.NeoForgeCommands;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(ModSync.MOD_ID)
public final class ModSyncNeoForge {
    public ModSyncNeoForge() {
        ModSync.init();
        NeoForgeCommands.init();
        if (FMLEnvironment.dist.isClient()) {
            ModSyncNeoForgeClient.init();
        }
    }
}
//?}

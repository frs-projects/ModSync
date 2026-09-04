//? if neoforge {
package net.frsprojects.modsync;

import net.neoforged.fml.common.Mod;

@Mod(ModSync.MOD_ID)
public final class ModSyncNeoForge {
    public ModSyncNeoForge() {
        ModSync.init();
    }
}
//?}

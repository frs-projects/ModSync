package net.frsprojects.modsync;

import net.frsprojects.modsync.core.ModSyncCore;

/**
 * Loader-independent entry point. Every loader's entrypoint funnels into here so
 * that the loader-specific classes stay as thin as possible.
 */
public final class ModSync {
    public static final String MOD_ID = /*$ mod_id*/ "modsync";
    public static final String VERSION = /*$ mod_version*/ "0.0.0";
    public static final String MINECRAFT = /*$ minecraft*/ "0";

    private ModSync() {}

    public static void init() {
        System.out.println("[ModSync] " + VERSION + " on Minecraft " + MINECRAFT
            + " (manifest format v" + ModSyncCore.MANIFEST_FORMAT_VERSION + ")");
    }
}

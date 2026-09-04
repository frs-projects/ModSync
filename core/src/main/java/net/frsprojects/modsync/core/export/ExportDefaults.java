package net.frsprojects.modsync.core.export;

import net.frsprojects.modsync.core.manifest.Policy;
import net.frsprojects.modsync.core.manifest.Side;

/**
 * The policy and side a freshly exported entry starts life with.
 *
 * <p>Nobody should be blocked from joining because they declined a shaderpack, and a resource
 * pack the dedicated server never reads is client business — so those two roots default to
 * optional/client and everything else to require/both. The defaults only have to be right
 * often enough that an admin edits a handful of lines instead of all of them.
 */
public final class ExportDefaults {

    private ExportDefaults() {}

    public static Policy policyFor(String root) {
        switch (root) {
            case "resourcepacks":
            case "shaderpacks":
                return Policy.OPTIONAL;
            default:
                return Policy.REQUIRE;
        }
    }

    public static Side sideFor(String root) {
        switch (root) {
            case "resourcepacks":
            case "shaderpacks":
                return Side.CLIENT;
            default:
                return Side.BOTH;
        }
    }

    /**
     * Whether files under this root are worth asking Modrinth and CurseForge about. A config
     * file is pack-specific and has no project behind it, so querying it spends a request to
     * learn nothing.
     */
    public static boolean supportsLookup(String root) {
        return "mods".equals(root) || "resourcepacks".equals(root) || "shaderpacks".equals(root);
    }
}

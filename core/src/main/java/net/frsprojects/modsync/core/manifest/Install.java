package net.frsprojects.modsync.core.manifest;

import java.util.Locale;

/**
 * When an entry's file is (re)written. Separate from {@link Policy}, which says whether the
 * file is wanted at all.
 *
 * <p>Config files are the reason this exists: many mods rewrite their config on startup, so a
 * shipped config never keeps its hash, and enforcing it would overwrite the player's settings
 * on every join.
 */
public enum Install {
    /** The local file must match the hash; a different one is replaced. */
    EXACT("exact"),
    /** Placed only when nothing is at the path; an existing file is left as it is. */
    IF_MISSING("ifMissing");

    private final String jsonName;

    Install(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    /** Case-insensitive, and tolerant of {@code if_missing} / {@code if-missing}. */
    public static Install fromJson(String raw) {
        String key = raw.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        for (Install i : values()) {
            if (i.jsonName.toLowerCase(Locale.ROOT).equals(key)) {
                return i;
            }
        }
        return null;
    }
}

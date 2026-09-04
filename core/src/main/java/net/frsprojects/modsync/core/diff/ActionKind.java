package net.frsprojects.modsync.core.diff;

/** What ModSync intends to do with one path. */
public enum ActionKind {
    /** Already present with the right content. Nothing to do. */
    KEEP,
    /** Not present; must be downloaded. */
    INSTALL,
    /** Present with different content; the old file is quarantined and the new one placed. */
    REPLACE,
    /**
     * The wanted content is already in the cache, so no network is needed. Covers both a
     * missing file and a wrong-version file; in the latter case the existing file is still
     * quarantined first, exactly as {@link #REPLACE} would.
     */
    RESTORE,
    /** Listed with policy {@code forbid} and present; moved to quarantine. */
    QUARANTINE_FORBIDDEN,
    /** Not mentioned by the manifest; moved to quarantine under the whitelist model. */
    QUARANTINE_UNLISTED,
    /** Not mentioned, but protected by an alwaysKeep rule or because it is ModSync itself. */
    PROTECTED,
    /** Required, but there is no URL and no cached copy. The join cannot proceed. */
    BLOCKED;

    /** Whether this action needs bytes off the network. */
    public boolean needsDownload() {
        return this == INSTALL || this == REPLACE;
    }

    /** Whether this action changes the filesystem. */
    public boolean mutates() {
        return this != KEEP && this != PROTECTED && this != BLOCKED;
    }
}

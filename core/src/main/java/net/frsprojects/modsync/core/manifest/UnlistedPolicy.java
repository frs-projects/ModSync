package net.frsprojects.modsync.core.manifest;

/**
 * How the client treats files that the manifest does not mention at all.
 *
 * <p>{@link #QUARANTINE} makes the manifest an effective whitelist, which is what stops
 * two versions of the same mod coexisting. Quarantine is always a move, never a delete.
 */
public enum UnlistedPolicy {
    /** Move unlisted managed files aside for this profile. */
    QUARANTINE,
    /** Leave unlisted files alone. */
    KEEP
}

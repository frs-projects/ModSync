package net.frsprojects.modsync.core.manifest;

/**
 * Which side an entry belongs on. A server's manifest legitimately lists server-only
 * files, and the client must skip those rather than download them.
 */
public enum Side {
    CLIENT,
    SERVER,
    BOTH;

    public boolean appliesToClient() {
        return this == CLIENT || this == BOTH;
    }

    public boolean appliesToServer() {
        return this == SERVER || this == BOTH;
    }
}

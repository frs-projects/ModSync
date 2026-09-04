package net.frsprojects.modsync.core.manifest;

/**
 * What the server expects the client to do with an entry.
 *
 * <p>This replaces a plain {@code required} boolean because a boolean cannot express
 * "this file must not be present", which a pack needs in order to retire an outdated
 * jar or ban a client mod.
 */
public enum Policy {
    /** Must be present and hash-matched, or the client cannot join. */
    REQUIRE,
    /** Optional, but pre-selected in the diff UI. */
    RECOMMEND,
    /** Optional, not pre-selected. */
    OPTIONAL,
    /** Must NOT be present; the client moves it to quarantine. */
    FORBID;

    /** True when declining this entry should block joining. */
    public boolean isMandatory() {
        return this == REQUIRE || this == FORBID;
    }

    /** Whether the diff UI ticks this entry by default. */
    public boolean defaultSelected() {
        return this != OPTIONAL;
    }
}

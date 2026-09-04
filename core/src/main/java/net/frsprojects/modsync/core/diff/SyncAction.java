package net.frsprojects.modsync.core.diff;

import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Policy;

/**
 * One line of the diff the user is shown.
 *
 * @param kind what will happen
 * @param path game-directory-relative target
 * @param entry the manifest entry that drove this, or null for unlisted local files
 * @param existing the local file currently at {@code path}, or null when there is none
 * @param reason a short human-readable explanation, shown in the UI
 */
public record SyncAction(
    ActionKind kind,
    String path,
    ManifestEntry entry,
    LocalFile existing,
    String reason
) {

    /** Whether declining this action blocks joining. */
    public boolean isMandatory() {
        return entry != null && entry.policy().isMandatory();
    }

    /** Whether the user may untick this in the diff UI. */
    public boolean isOptional() {
        return entry != null && !entry.policy().isMandatory();
    }

    /** Whether the diff UI ticks this by default. */
    public boolean selectedByDefault() {
        if (entry == null) {
            // Quarantining an unlisted file is part of the whitelist contract, not a choice.
            return kind.mutates();
        }
        return entry.policy() == Policy.REQUIRE
            || entry.policy() == Policy.FORBID
            || entry.defaultEnabled();
    }

    /** Bytes this action will pull over the network; zero when it needs none. */
    public long downloadBytes() {
        if (!kind.needsDownload() || entry == null) {
            return 0L;
        }
        return Math.max(entry.size(), 0L);
    }

    public String label() {
        return entry != null ? entry.label() : path;
    }
}

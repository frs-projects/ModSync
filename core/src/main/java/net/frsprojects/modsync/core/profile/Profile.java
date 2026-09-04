package net.frsprojects.modsync.core.profile;

import net.frsprojects.modsync.core.diff.LocalFile;
import net.frsprojects.modsync.core.manifest.SyncManifest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A snapshot of what one pack's file set looks like.
 *
 * <p>Diffing does not need this — the manifest is authoritative — but four things do:
 * restoring {@code _base} (the player's own mods, captured before any server touched them),
 * switching back to a pack while offline, knowing which profile {@code mods/} currently
 * holds, and working out which cached blobs are still referenced when pruning.
 */
public record Profile(
    int formatVersion,
    String profileId,
    String packId,
    String packName,
    String packVersion,
    Instant appliedAt,
    List<ProfileFile> files
) {

    public static final int CURRENT_FORMAT_VERSION = 1;

    /** One file the profile expects to be present. */
    public record ProfileFile(String path, String sha512, long size) {}

    public static Profile fromManifest(String profileId, SyncManifest manifest,
            List<LocalFile> installed) {
        List<ProfileFile> files = new ArrayList<>(installed.size());
        for (LocalFile f : installed) {
            files.add(new ProfileFile(f.path(), f.sha512(), f.size()));
        }
        return new Profile(CURRENT_FORMAT_VERSION, profileId, manifest.packId(),
            manifest.packName(), manifest.packVersion(), Instant.now(), List.copyOf(files));
    }

    /** The profile capturing the player's own mods, before any server was involved. */
    public static Profile base(List<LocalFile> current) {
        List<ProfileFile> files = new ArrayList<>(current.size());
        for (LocalFile f : current) {
            files.add(new ProfileFile(f.path(), f.sha512(), f.size()));
        }
        return new Profile(CURRENT_FORMAT_VERSION, ModSyncPaths.BASE_PROFILE, null,
            "Your own mods", "0", Instant.now(), List.copyOf(files));
    }

    public Set<String> hashes() {
        Set<String> out = new LinkedHashSet<>();
        for (ProfileFile f : files) {
            if (f.sha512() != null) {
                out.add(f.sha512());
            }
        }
        return out;
    }
}

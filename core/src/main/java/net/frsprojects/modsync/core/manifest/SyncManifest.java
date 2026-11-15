package net.frsprojects.modsync.core.manifest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A server's complete statement about what a client's game directory should contain.
 *
 * <p>The envelope exists so the format can be versioned and extended; a bare top-level
 * array (the original sketch) could carry neither pack identity nor a format version.
 */
public record SyncManifest(
    int formatVersion,
    /** Stable pack identity. Survives IP changes and lets a network of servers share one
     *  profile. Clients fall back to a hash of host:port when this is absent. */
    String packId,
    String packName,
    /** Opaque to the client; only equality matters, for "already up to date" checks. */
    String packVersion,
    Instant generatedAt,
    UnlistedPolicy unlistedPolicy,
    /** Where {@link UnlistedPolicy#QUARANTINE} applies. Empty means every top-level folder
     *  that an entry uses, which is how manifests behaved before this field existed. */
    List<PathGlob> managed,
    List<ManifestEntry> files
) {

    /** A manifest without a {@code managed} list: unlisted files are judged per root. */
    public SyncManifest(int formatVersion, String packId, String packName, String packVersion,
            Instant generatedAt, UnlistedPolicy unlistedPolicy, List<ManifestEntry> files) {
        this(formatVersion, packId, packName, packVersion, generatedAt, unlistedPolicy,
            List.of(), files);
    }

    /** The manifest format this build writes. */
    public static final int CURRENT_FORMAT_VERSION = 1;

    /** Entries relevant to a client on the given loader and Minecraft version. */
    public List<ManifestEntry> forClient(String loader, String mcVersion) {
        return filter(loader, mcVersion, true);
    }

    /** Entries relevant to a server on the given loader and Minecraft version. */
    public List<ManifestEntry> forServer(String loader, String mcVersion) {
        return filter(loader, mcVersion, false);
    }

    private List<ManifestEntry> filter(String loader, String mcVersion, boolean clientSide) {
        List<ManifestEntry> out = new ArrayList<>(files.size());
        for (ManifestEntry e : files) {
            if (e.appliesTo(loader, mcVersion, clientSide)) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }
}

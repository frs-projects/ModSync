package net.frsprojects.modsync.core.export;

import java.nio.file.Path;
import java.util.List;

/** Everything one export needs. */
public record ExportRequest(
    Path gameDir,
    /** A single allowlisted root, e.g. {@code mods}. */
    String folder,
    /** Display name for the pack, or null to derive one from the folder. */
    String packName,
    /** Pack version, or null to use the export timestamp. */
    String packVersion,
    /** Hosts to ask about URLs, in priority order. Empty means an offline export. */
    List<ModMetadataLookup> lookups
) {

    public ExportRequest {
        lookups = List.copyOf(lookups);
    }

    public static ExportRequest offline(Path gameDir, String folder) {
        return new ExportRequest(gameDir, folder, null, null, List.of());
    }
}

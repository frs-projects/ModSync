package net.frsprojects.modsync.core.export;

import net.frsprojects.modsync.core.manifest.ManualDownload;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Turns local files into download URLs by asking a mod host which project they belong to.
 *
 * <p>An interface rather than a concrete client so {@link ExportService} can be tested without
 * a network: the export logic is the part worth testing, and the API shapes are not ours to
 * change.
 */
public interface ModMetadataLookup {

    /**
     * What a host knew about one file. Any field may be null if the host did not say.
     *
     * @param manual where a player can download the file by hand, for a host that knows the
     *     file but will not let ModSync fetch it; only meaningful when {@code url} is null
     */
    record Resolved(String id, String url, ManualDownload manual) {

        public Resolved(String id, String url) {
            this(id, url, null);
        }
    }

    /** Human-readable host name, used in progress messages. */
    String name();

    /**
     * Resolves what it can, keyed by {@link ExportCandidate#path()}. A missing key means the
     * host did not recognise the file, which is normal and not an error.
     */
    Map<String, Resolved> resolve(List<ExportCandidate> candidates) throws IOException;
}

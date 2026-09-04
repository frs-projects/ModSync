package net.frsprojects.modsync.core.export;

import net.frsprojects.modsync.core.config.ModSyncConfig;
import net.frsprojects.modsync.core.manifest.ManifestCodec;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Policy;
import net.frsprojects.modsync.core.manifest.Side;
import net.frsprojects.modsync.core.manifest.SyncManifest;
import net.frsprojects.modsync.core.manifest.UnlistedPolicy;
import net.frsprojects.modsync.core.profile.ModSyncPaths;
import net.frsprojects.modsync.core.security.HostAllowlist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a folder into a publishable manifest.
 *
 * <p>The output is format v1 — the same format the sync client parses — so an export can be
 * handed straight to a server without a conversion step, and so a round-trip through
 * {@link ManifestCodec} is a real test of the exporter.
 *
 * <p>Every export writes a new timestamped file. An earlier export may already have been
 * hand-edited and published, and silently replacing it would destroy work that cannot be
 * recovered from anywhere else.
 */
public final class ExportService {

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private ExportService() {}

    /** The lookups implied by the user's config, or an empty list if none can run. */
    public static List<ModMetadataLookup> defaultLookups(Path gameDir, ModSyncConfig config,
            JsonHttp http, ExportProgress progress) {
        List<ModMetadataLookup> lookups = new ArrayList<>();
        lookups.add(new ModrinthLookup(http));
        String key = config.curseForgeApiKey();
        if (key != null && !key.isBlank()) {
            lookups.add(new CurseForgeLookup(http, key.trim(), gameDir));
        } else {
            progress.message("No curseForgeApiKey in modsync.json, so only Modrinth will be "
                + "asked. CurseForge-only files will have no URL.");
        }
        return List.copyOf(lookups);
    }

    /** The allowlist the lookups run under. */
    public static HostAllowlist lookupAllowlist(ModSyncConfig config) {
        return HostAllowlist.defaults().plusUserApproved(config.approvedHosts());
    }

    public static Path export(ExportRequest request, ExportProgress progress)
            throws ExportException, IOException {
        String folder = request.folder();

        progress.message("Scanning " + folder + "/ ...");
        List<ExportCandidate> candidates = new ExportScanner(request.gameDir()).scan(folder);
        if (candidates.isEmpty()) {
            throw new ExportException("There is nothing to export: " + folder + "/ is empty.");
        }
        progress.message("Hashed " + candidates.size() + " file"
            + (candidates.size() == 1 ? "" : "s") + ".");

        Map<String, ModMetadataLookup.Resolved> resolved =
            lookUp(request, candidates, progress);

        List<ManifestEntry> entries = new ArrayList<>(candidates.size());
        int withUrl = 0;
        for (ExportCandidate c : candidates) {
            ModMetadataLookup.Resolved r = resolved.get(c.path());
            String url = r == null ? null : r.url();
            List<String> urls = url == null ? List.of() : List.of(url);
            if (url != null) {
                withUrl++;
            }
            Policy policy = ExportDefaults.policyFor(c.root());
            entries.add(new ManifestEntry(
                r == null ? null : r.id(),
                c.fileName(),
                null,
                c.path(),
                c.size(),
                c.hashes(),
                urls,
                policy,
                ExportDefaults.sideFor(c.root()),
                List.of(),
                List.of(),
                null,
                policy.defaultSelected()));
        }

        Instant now = Instant.now();
        String stamp = STAMP.format(now);
        String packVersion = blankToNull(request.packVersion()) == null
            ? stamp
            : request.packVersion().trim();
        String packName = blankToNull(request.packName()) == null
            ? "Exported " + folder
            : request.packName().trim();

        SyncManifest manifest = new SyncManifest(
            SyncManifest.CURRENT_FORMAT_VERSION,
            toPackId(packName, folder),
            packName,
            packVersion,
            now,
            UnlistedPolicy.QUARANTINE,
            List.copyOf(entries));

        ModSyncPaths paths = new ModSyncPaths(request.gameDir());
        Path output = paths.exportFile("modsync-export-" + folder + "-" + stamp + ".json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, ManifestCodec.write(manifest), StandardCharsets.UTF_8);

        int unresolved = entries.size() - withUrl;
        progress.finished(output, entries.size(), withUrl, unresolved);
        return output;
    }

    private static Map<String, ModMetadataLookup.Resolved> lookUp(ExportRequest request,
            List<ExportCandidate> candidates, ExportProgress progress) {
        Map<String, ModMetadataLookup.Resolved> resolved = new HashMap<>();
        if (request.lookups().isEmpty()) {
            return resolved;
        }
        List<ExportCandidate> askable = new ArrayList<>();
        for (ExportCandidate c : candidates) {
            if (ExportDefaults.supportsLookup(c.root())) {
                askable.add(c);
            }
        }
        if (askable.isEmpty()) {
            progress.message("Nothing under " + request.folder()
                + "/ has a project behind it, so no lookups were made.");
            return resolved;
        }

        for (ModMetadataLookup lookup : request.lookups()) {
            List<ExportCandidate> remaining = new ArrayList<>();
            for (ExportCandidate c : askable) {
                ModMetadataLookup.Resolved r = resolved.get(c.path());
                if (r == null || r.url() == null) {
                    remaining.add(c);
                }
            }
            if (remaining.isEmpty()) {
                break;
            }
            progress.message("Asking " + lookup.name() + " about " + remaining.size()
                + " file" + (remaining.size() == 1 ? "" : "s") + " ...");
            try {
                lookup.resolve(remaining).forEach((path, r) -> {
                    ModMetadataLookup.Resolved existing = resolved.get(path);
                    if (existing == null || existing.url() == null) {
                        resolved.put(path, r);
                    }
                });
            } catch (IOException e) {
                // One host being down must not throw away a whole export: the manifest is
                // still correct, it just has fewer URLs filled in.
                progress.message(lookup.name() + " lookup failed (" + e.getMessage()
                    + "). Continuing without it.");
            }
        }
        return resolved;
    }

    /**
     * {@code packId} becomes a directory name on every client that syncs this pack, and
     * {@link ManifestCodec} refuses anything outside {@code [A-Za-z0-9][A-Za-z0-9._-]{0,63}}
     * on parse — so an id we generate has to clear the same bar we would enforce on a server.
     */
    static String toPackId(String packName, String folder) {
        StringBuilder sb = new StringBuilder(packName.length());
        for (int i = 0; i < packName.length(); i++) {
            char c = packName.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            // Runs of punctuation collapse to one separator, so "My Pack (2024)" does not
            // become "my-pack--2024-".
            if (ok) {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        // The first character has a stricter rule than the rest, and a trailing separator is
        // legal but reads as a typo in a directory listing.
        int start = 0;
        while (start < sb.length() && !Character.isLetterOrDigit(sb.charAt(start))) {
            start++;
        }
        int end = sb.length();
        while (end > start && !Character.isLetterOrDigit(sb.charAt(end - 1))) {
            end--;
        }
        String id = sb.substring(start, end);
        if (id.isEmpty()) {
            id = "export-" + folder;
        }
        id = id.toLowerCase(Locale.ROOT);
        return id.length() > 64 ? id.substring(0, 64) : id;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

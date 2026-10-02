package net.frsprojects.modsync.core.sync;

import net.frsprojects.modsync.core.apply.Journal;
import net.frsprojects.modsync.core.config.ModSyncConfig;
import net.frsprojects.modsync.core.diff.Differ;
import net.frsprojects.modsync.core.diff.FileStateCache;
import net.frsprojects.modsync.core.diff.KeepRules;
import net.frsprojects.modsync.core.diff.LocalFile;
import net.frsprojects.modsync.core.diff.LocalScanner;
import net.frsprojects.modsync.core.diff.SyncAction;
import net.frsprojects.modsync.core.diff.SyncPlan;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.SyncManifest;
import net.frsprojects.modsync.core.net.DownloadProgress;
import net.frsprojects.modsync.core.net.Downloader;
import net.frsprojects.modsync.core.profile.ContentCache;
import net.frsprojects.modsync.core.profile.ModSyncPaths;
import net.frsprojects.modsync.core.security.HostAllowlist;
import net.frsprojects.modsync.core.security.PathSandbox;
import net.frsprojects.modsync.core.security.SandboxException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * One sync against one manifest, from fetch to journal: the sequence the client runs when the
 * player joins a server that has a manifest configured.
 *
 * <ol>
 *   <li>{@link #prepare} fetches the manifest, refuses it if any path escapes the sandbox,
 *       scans the roots it manages and diffs them into a {@link SyncPlan} for the player.
 *   <li>{@link #download} pulls the accepted files into the content cache.
 *   <li>{@link #writeJournal} records the accepted changes for the applier, which swaps them
 *       in after the game has exited.
 * </ol>
 *
 * <p>Nothing here touches {@code mods/}: every filesystem change goes through the journal.
 */
public final class SyncSession {

    private final ModSyncPaths paths;
    private final ModSyncConfig config;
    private final String manifestUrl;
    private final String profileId;
    private final String userAgent;
    private final SyncPlan plan;
    private final OptionalChoices choices;
    private HostAllowlist baseAllowlist = HostAllowlist.defaults();

    private SyncSession(ModSyncPaths paths, ModSyncConfig config, String manifestUrl,
            String profileId, String userAgent, SyncPlan plan, OptionalChoices choices) {
        this.paths = paths;
        this.config = config;
        this.manifestUrl = manifestUrl;
        this.profileId = profileId;
        this.userAgent = userAgent;
        this.plan = plan;
        this.choices = choices;
    }

    /**
     * Fetches the manifest and works out what would change. Blocking: call off the main thread.
     *
     * @param fallbackProfileId profile to use when the manifest has no {@code packId},
     *     typically derived from the server address
     * @param loader this client's loader id, e.g. {@code neoforge}
     * @param mcVersion this client's Minecraft version
     */
    public static SyncSession prepare(ModSyncPaths paths, ModSyncConfig config,
            String manifestUrl, String fallbackProfileId, String loader, String mcVersion,
            String userAgent) throws IOException {
        SyncManifest manifest = new ManifestFetcher(userAgent).fetch(manifestUrl);
        return prepare(paths, config, manifestUrl, manifest, fallbackProfileId, loader,
            mcVersion, userAgent);
    }

    /** {@link #prepare} with the manifest already in hand. */
    public static SyncSession prepare(ModSyncPaths paths, ModSyncConfig config,
            String manifestUrl, SyncManifest manifest, String fallbackProfileId, String loader,
            String mcVersion, String userAgent) throws IOException {
        List<ManifestEntry> entries = manifest.forClient(loader, mcVersion);

        // The codec only checks a path's shape. Whether it may be written is decided here,
        // before anything is scanned or downloaded, and one bad path rejects the whole
        // manifest: a pack that tries to escape the sandbox is not a pack to half-apply.
        PathSandbox sandbox = new PathSandbox(paths.gameDir());
        Set<String> roots = new TreeSet<>();
        for (ManifestEntry entry : entries) {
            try {
                sandbox.resolve(entry.path());
            } catch (SandboxException e) {
                throw new IOException("Refusing the manifest at " + manifestUrl + ": "
                    + e.getMessage(), e);
            }
            int slash = entry.path().indexOf('/');
            if (slash > 0) {
                roots.add(entry.path().substring(0, slash));
            }
        }

        paths.createDirectories();
        FileStateCache stateCache = FileStateCache.load(paths.stateCache());
        List<LocalFile> local = new LocalScanner(paths.gameDir(), stateCache).scan(roots);
        stateCache.save(paths.stateCache());

        String profileId = manifest.packId() != null && !manifest.packId().isBlank()
            ? manifest.packId() : fallbackProfileId;
        OptionalChoices choices = OptionalChoices.load(paths.optionalChoices(profileId));

        KeepRules keep = KeepRules.of(config.alwaysKeep());
        SyncPlan plan = new Differ(new ContentCache(paths), keep)
            .diff(manifest, entries, local, choices.declined());

        return new SyncSession(paths, config, manifestUrl, profileId, userAgent, plan, choices);
    }

    /** Tests only: a local HTTP server is not on the real allowlist, and should not be. */
    SyncSession withBaseAllowlist(HostAllowlist allowlist) {
        this.baseAllowlist = allowlist;
        return this;
    }

    public SyncPlan plan() {
        return plan;
    }

    public String manifestUrl() {
        return manifestUrl;
    }

    public String profileId() {
        return profileId;
    }

    /** What the player has picked from this pack's optional files. Mutable; see
     *  {@link #saveChoices}. */
    public OptionalChoices choices() {
        return choices;
    }

    public void saveChoices() throws IOException {
        choices.save(paths.optionalChoices(profileId));
    }

    /**
     * Downloads every accepted entry that is not already cached. Blocking.
     *
     * @return the entries that could not be fetched; empty on success
     */
    public List<Downloader.Failure> download(Set<String> accepted, DownloadProgress progress)
            throws IOException {
        List<ManifestEntry> wanted = new ArrayList<>();
        for (SyncAction action : plan.requiringDownload()) {
            if (accepted.contains(action.path())) {
                wanted.add(action.entry());
            }
        }
        if (wanted.isEmpty()) {
            return List.of();
        }
        HostAllowlist allowlist = baseAllowlist
            .plusUserApproved(config.approvedHosts())
            .plusManifestHost(manifestUrl);
        try (Downloader downloader = new Downloader(paths, new ContentCache(paths), allowlist,
                config.parallelDownloads(), userAgent)) {
            return downloader.fetchAll(wanted, progress);
        }
    }

    /**
     * Writes the accepted changes to the journal, replacing any earlier pending one: the
     * newest plan was made against the current state of the game directory, an older one was
     * not.
     *
     * @return the number of journalled operations; zero means there is nothing to apply
     */
    public int writeJournal(Set<String> accepted) throws IOException {
        Journal journal = plan.toJournal(accepted, profileId, paths);
        if (journal.isEmpty()) {
            return 0;
        }
        journal.writeTo(paths.journal());
        return journal.ops().size();
    }
}

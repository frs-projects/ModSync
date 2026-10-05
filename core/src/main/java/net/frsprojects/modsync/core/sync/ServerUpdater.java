package net.frsprojects.modsync.core.sync;

import net.frsprojects.modsync.core.apply.JournalApplier;
import net.frsprojects.modsync.core.config.ServerSyncConfig;
import net.frsprojects.modsync.core.diff.SyncAction;
import net.frsprojects.modsync.core.diff.SyncPlan;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Side;
import net.frsprojects.modsync.core.manifest.SyncManifest;
import net.frsprojects.modsync.core.net.DownloadProgress;
import net.frsprojects.modsync.core.net.Downloader;
import net.frsprojects.modsync.core.profile.ModSyncPaths;
import net.frsprojects.modsync.core.security.HostAllowlist;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Keeps a dedicated server's own game directory in line with its manifest.
 *
 * <p>The same sequence as a client's join, minus the player: fetch, diff against the
 * {@link Side#SERVER} entries, take the default selection (required and recommended files,
 * nothing optional), download, journal. The journal is applied when the server stops, because
 * the running server already has its mods loaded and on Windows holds every jar locked.
 *
 * <p>Headless and free of Minecraft, so the server layer only decides when to call it and where
 * the messages go.
 */
public final class ServerUpdater {

    /** Profile for a manifest without a {@code packId}; a server only ever has one pack. */
    static final String FALLBACK_PROFILE = "server";

    public enum Outcome {
        /** No {@code manifestUrl}; the server does not sync. */
        NOT_CONFIGURED,
        /** Nothing to change. */
        UP_TO_DATE,
        /** Changes are downloaded and journalled, waiting for the server to stop. */
        STAGED,
        /**
         * A required file has no URL and is not cached, or has to be downloaded by hand and
         * is not in {@code modsync/import/} yet; nothing was staged.
         */
        BLOCKED,
        /** The manifest or a download failed; nothing was staged. */
        FAILED
    }

    /**
     * @param summary one line for the log or the command output
     * @param details one line per change or problem, possibly empty
     */
    public record Result(Outcome outcome, String summary, List<String> details) {}

    private final ModSyncPaths paths;
    private final String loader;
    private final String mcVersion;
    private final String userAgent;
    private final Object journalLock = new Object();
    private HostAllowlist baseAllowlist = HostAllowlist.defaults();

    public ServerUpdater(ModSyncPaths paths, String loader, String mcVersion, String userAgent) {
        this.paths = paths;
        this.loader = loader;
        this.mcVersion = mcVersion;
        this.userAgent = userAgent;
    }

    /** Tests only: a local HTTP server is not on the real allowlist, and should not be. */
    ServerUpdater withBaseAllowlist(HostAllowlist allowlist) {
        this.baseAllowlist = allowlist;
        return this;
    }

    /**
     * Checks the manifest and stages whatever has to change. Blocking: never call it on the
     * server thread. Never throws for an expected failure; the result says what went wrong.
     *
     * @param progress receives a line per downloaded or failed file
     */
    public Result check(Consumer<String> progress) {
        ServerSyncConfig config;
        try {
            // Re-read every time, so an edit takes effect with /modsync update.
            config = ServerSyncConfig.loadOrCreate(paths.serverConfig());
        } catch (IOException | RuntimeException e) {
            return failed("The server config is unusable: " + e.getMessage());
        }
        if (!config.isConfigured()) {
            return new Result(Outcome.NOT_CONFIGURED, "No manifestUrl in "
                + paths.gameDir().relativize(paths.serverConfig()) + "; not syncing", List.of());
        }

        SyncSession session;
        try {
            SyncManifest manifest = new ManifestFetcher(userAgent).fetch(config.manifestUrl());
            session = SyncSession.prepare(paths, config.toSyncConfig(), config.manifestUrl(),
                manifest, FALLBACK_PROFILE, loader, mcVersion, userAgent, Side.SERVER)
                .withBaseAllowlist(baseAllowlist);
        } catch (IOException | RuntimeException e) {
            return failed(e.getMessage());
        }

        SyncPlan plan = session.plan();
        String pack = describe(plan.manifest());
        if (!plan.canProceed()) {
            List<String> details = new ArrayList<>();
            for (SyncAction a : plan.blocked()) {
                details.add("! " + a.label() + ": " + a.reason());
            }
            return new Result(Outcome.BLOCKED, pack + " cannot be applied: "
                + details.size() + " required file(s) cannot be obtained", details);
        }

        Set<String> accepted = new LinkedHashSet<>(plan.defaultSelection());
        List<String> skipped = new ArrayList<>();
        Result manual = importManual(session, accepted, pack, skipped);
        if (manual != null) {
            return manual;
        }
        if (accepted.isEmpty()) {
            discardPending();
            return new Result(Outcome.UP_TO_DATE, pack + " is up to date", List.of());
        }

        List<Downloader.Failure> failures;
        try {
            failures = session.download(accepted, progressSink(progress));
        } catch (IOException | RuntimeException e) {
            return failed("Downloading " + pack + " failed: " + e.getMessage());
        }
        if (!failures.isEmpty()) {
            List<String> details = new ArrayList<>();
            for (Downloader.Failure f : failures) {
                details.add("! " + f.entry().label() + ": " + f.reason());
            }
            return new Result(Outcome.FAILED, failures.size()
                + " file(s) of " + pack + " could not be downloaded; nothing was staged", details);
        }

        int ops;
        try {
            synchronized (journalLock) {
                ops = session.writeJournal(accepted);
            }
        } catch (IOException e) {
            return failed("Could not write the journal: " + e.getMessage());
        }
        if (ops == 0) {
            discardPending();
            return new Result(Outcome.UP_TO_DATE, pack + " is up to date", List.of());
        }
        List<String> details = new ArrayList<>(describeChanges(plan, accepted));
        details.addAll(skipped);
        return new Result(Outcome.STAGED, pack + ": " + accepted.size()
            + " change(s) staged, applied when the server stops", details);
    }

    /**
     * Picks up hand-downloaded files from {@code modsync/import/}. A server has no browser, so
     * the admin downloads them and drops them there.
     *
     * <p>A missing required file blocks the update, as any unobtainable file would. A missing
     * recommended one is left out of {@code accepted} instead: holding back a whole update for
     * a file the server could run without would be worse than skipping it until it arrives.
     *
     * @param skipped receives a line per recommended file left out
     * @return a BLOCKED result, or null to carry on
     */
    private Result importManual(SyncSession session, Set<String> accepted, String pack,
            List<String> skipped) {
        List<SyncAction> manual = session.plan().manual(accepted);
        if (manual.isEmpty()) {
            return null;
        }
        try {
            Files.createDirectories(paths.importDir());
        } catch (IOException ignored) {
            // Only means the admin has to create it; the message below names it.
        }
        ManualImports imports = session.manualImports(accepted);
        imports.scan(List.of(paths.importDir()));

        String dir = paths.gameDir().relativize(paths.importDir()).toString().replace('\\', '/');
        List<String> missing = new ArrayList<>();
        for (SyncAction a : manual) {
            ManifestEntry e = a.entry();
            if (imports.isDone(e)) {
                continue;
            }
            String line = a.label() + ": download " + e.manual().url() + " into " + dir + "/";
            if (a.isMandatory()) {
                missing.add("! " + line);
            } else {
                accepted.remove(a.path());
                skipped.add("? skipped " + line);
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        missing.add("Then run /modsync update, or restart the server.");
        return new Result(Outcome.BLOCKED, pack + " cannot be applied: "
            + (missing.size() - 1) + " required file(s) have to be downloaded by hand", missing);
    }

    /** Whether a journal is waiting to be applied. */
    public boolean hasPending() {
        return Files.exists(paths.journal());
    }

    /**
     * Applies the pending journal in this process. Meant for shutdown, after the server has
     * stopped: on Linux and macOS a jar can be moved while the JVM still has it open. On
     * Windows the moves fail on locked jars, the journal stays in place, and the caller falls
     * back to the detached applier, which replays it from the top once this process is gone.
     *
     * @return null when there was nothing to apply
     */
    public JournalApplier.Result applyPending() throws IOException {
        synchronized (journalLock) {
            if (!Files.exists(paths.journal())) {
                return null;
            }
            return new JournalApplier(paths).applyPending();
        }
    }

    /**
     * Drops a journal staged against an older manifest. When the newest manifest needs
     * nothing, applying that one would undo it.
     */
    private void discardPending() {
        synchronized (journalLock) {
            try {
                Files.deleteIfExists(paths.journal());
            } catch (IOException ignored) {
                // Harmless: the next check that stages something replaces it.
            }
        }
    }

    private static String describe(SyncManifest manifest) {
        String name = manifest.packName() != null && !manifest.packName().isBlank()
            ? manifest.packName() : manifest.packId() != null ? manifest.packId() : "The pack";
        return manifest.packVersion() != null && !manifest.packVersion().isBlank()
            ? name + " " + manifest.packVersion() : name;
    }

    private static List<String> describeChanges(SyncPlan plan, Set<String> accepted) {
        List<String> out = new ArrayList<>();
        for (SyncAction a : plan.changes()) {
            if (!accepted.contains(a.path())) {
                continue;
            }
            out.add(switch (a.kind()) {
                case INSTALL, RESTORE -> a.existing() == null
                    ? "+ " + a.path() : "~ " + a.path();
                case REPLACE -> "~ " + a.path();
                default -> "- " + a.path() + " (" + a.reason() + ")";
            });
        }
        return out;
    }

    private static DownloadProgress progressSink(Consumer<String> progress) {
        return new DownloadProgress() {
            @Override
            public void finished(String label) {
                progress.accept("Downloaded " + label);
            }

            @Override
            public void failed(String label, String reason) {
                progress.accept("Could not download " + label + ": " + reason);
            }
        };
    }

    private static Result failed(String summary) {
        return new Result(Outcome.FAILED, summary, List.of());
    }
}

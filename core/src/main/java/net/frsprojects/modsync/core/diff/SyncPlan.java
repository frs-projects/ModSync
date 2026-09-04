package net.frsprojects.modsync.core.diff;

import net.frsprojects.modsync.core.apply.Journal;
import net.frsprojects.modsync.core.apply.JournalOp;
import net.frsprojects.modsync.core.manifest.SyncManifest;
import net.frsprojects.modsync.core.profile.ModSyncPaths;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Everything ModSync proposes to do, ready to be shown to the user and then journalled. */
public record SyncPlan(SyncManifest manifest, List<SyncAction> actions) {

    public List<SyncAction> of(ActionKind kind) {
        return actions.stream().filter(a -> a.kind() == kind).toList();
    }

    /** Actions that change the filesystem, in the order they should be presented. */
    public List<SyncAction> changes() {
        return actions.stream().filter(a -> a.kind().mutates()).toList();
    }

    /** Required entries that cannot be satisfied. A non-empty list means joining is impossible. */
    public List<SyncAction> blocked() {
        return actions.stream()
            .filter(a -> a.kind() == ActionKind.BLOCKED && a.isMandatory())
            .toList();
    }

    /** True when nothing at all needs to change. */
    public boolean isUpToDate() {
        return changes().isEmpty();
    }

    /** True when the user could accept this plan and end up compatible. */
    public boolean canProceed() {
        return blocked().isEmpty();
    }

    public long downloadBytes() {
        return actions.stream().mapToLong(SyncAction::downloadBytes).sum();
    }

    public List<SyncAction> requiringDownload() {
        return actions.stream().filter(a -> a.kind().needsDownload()).toList();
    }

    /**
     * Converts accepted actions into a journal.
     *
     * <p>Ordering is deliberate: every move runs before every link, so replacing a file at
     * the same path cannot race its own quarantine. Within that, {@code mkdir} comes first
     * so neither later verb has to create directories on the fly.
     *
     * @param accepted paths the user agreed to, from the diff UI
     * @param profileId which profile's quarantine displaced files go to
     */
    public Journal toJournal(Set<String> accepted, String profileId, ModSyncPaths paths) {
        String quarantineRoot = paths.gameDir()
            .relativize(paths.quarantineDir(profileId))
            .toString()
            .replace('\\', '/');

        Set<String> dirs = new LinkedHashSet<>();
        List<JournalOp> moves = new ArrayList<>();
        List<JournalOp> links = new ArrayList<>();

        for (SyncAction action : actions) {
            if (!action.kind().mutates() || !accepted.contains(action.path())) {
                continue;
            }
            switch (action.kind()) {
                case QUARANTINE_UNLISTED, QUARANTINE_FORBIDDEN -> {
                    String target = quarantineRoot + "/" + action.path();
                    dirs.add(parentOf(target));
                    moves.add(JournalOp.move(action.path(), target));
                }
                case REPLACE -> {
                    String target = quarantineRoot + "/" + action.path();
                    dirs.add(parentOf(target));
                    moves.add(JournalOp.move(action.path(), target));
                    dirs.add(parentOf(action.path()));
                    links.add(JournalOp.link(action.entry().hashes().sha512(), action.path()));
                }
                case INSTALL, RESTORE -> {
                    // RESTORE with an existing file still displaces it first: the content
                    // differs, so the old file is the user's and must not simply vanish.
                    if (action.existing() != null) {
                        String target = quarantineRoot + "/" + action.path();
                        dirs.add(parentOf(target));
                        moves.add(JournalOp.move(action.path(), target));
                    }
                    dirs.add(parentOf(action.path()));
                    links.add(JournalOp.link(action.entry().hashes().sha512(), action.path()));
                }
                default -> throw new IllegalStateException("Unhandled: " + action.kind());
            }
        }

        List<JournalOp> ops = new ArrayList<>(dirs.size() + moves.size() + links.size());
        dirs.stream().filter(d -> !d.isEmpty()).map(JournalOp::mkdir).forEach(ops::add);
        ops.addAll(moves);
        ops.addAll(links);
        return new Journal(ops);
    }

    /** Every action that changes the filesystem and is ticked by default. */
    public Set<String> defaultSelection() {
        Set<String> selected = new LinkedHashSet<>();
        for (SyncAction a : actions) {
            if (a.kind().mutates() && a.selectedByDefault()) {
                selected.add(a.path());
            }
        }
        return selected;
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}

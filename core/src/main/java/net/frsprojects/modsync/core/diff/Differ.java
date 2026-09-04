package net.frsprojects.modsync.core.diff;

import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Policy;
import net.frsprojects.modsync.core.manifest.SyncManifest;
import net.frsprojects.modsync.core.manifest.UnlistedPolicy;
import net.frsprojects.modsync.core.profile.ContentCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a manifest plus the current game directory into a {@link SyncPlan}.
 *
 * <p>The manifest is treated as a whitelist: anything inside a managed root that it does not
 * mention is quarantined. That is what makes updates work without any install-state
 * bookkeeping — {@code sodium-0.6.12.jar} simply becomes unlisted when the manifest moves to
 * {@code 0.6.13}, so the old jar is displaced and the new one installed, and two versions of
 * one mod can never coexist.
 */
public final class Differ {

    private final ContentCache cache;
    private final KeepRules keepRules;

    public Differ(ContentCache cache, KeepRules keepRules) {
        this.cache = cache;
        this.keepRules = keepRules;
    }

    /**
     * @param manifest the server's manifest, already filtered to this client's loader and
     *     Minecraft version via {@link SyncManifest#forClient}
     * @param entries the filtered entries (passed separately so the caller controls filtering)
     * @param local everything currently inside the managed roots
     */
    public SyncPlan diff(SyncManifest manifest, List<ManifestEntry> entries,
            List<LocalFile> local) {
        Map<String, LocalFile> byPath = new HashMap<>();
        for (LocalFile f : local) {
            byPath.put(f.path(), f);
        }

        List<SyncAction> actions = new ArrayList<>();
        Set<String> claimed = new HashSet<>();

        for (ManifestEntry entry : entries) {
            claimed.add(entry.path());
            actions.add(actionFor(entry, byPath.get(entry.path())));
        }

        if (manifest.unlistedPolicy() == UnlistedPolicy.QUARANTINE) {
            Set<String> managedRoots = managedRoots(entries);
            for (LocalFile f : local) {
                if (claimed.contains(f.path())) {
                    continue;
                }
                if (!managedRoots.contains(rootOf(f.path()))) {
                    // Outside every root the manifest touches, so not ModSync's business.
                    continue;
                }
                if (keepRules.isProtected(f.path())) {
                    actions.add(new SyncAction(ActionKind.PROTECTED, f.path(), null, f,
                        keepRules.reasonFor(f.path())));
                } else {
                    actions.add(new SyncAction(ActionKind.QUARANTINE_UNLISTED, f.path(), null, f,
                        "not part of this pack"));
                }
            }
        }

        return new SyncPlan(manifest, List.copyOf(actions));
    }

    private SyncAction actionFor(ManifestEntry entry, LocalFile existing) {
        String path = entry.path();

        if (entry.policy() == Policy.FORBID) {
            if (existing == null) {
                return new SyncAction(ActionKind.KEEP, path, entry, null,
                    "already absent");
            }
            if (keepRules.isProtected(path)) {
                return new SyncAction(ActionKind.PROTECTED, path, entry, existing,
                    keepRules.reasonFor(path));
            }
            return new SyncAction(ActionKind.QUARANTINE_FORBIDDEN, path, entry, existing,
                "this pack forbids it");
        }

        String wanted = entry.hashes().sha512();

        if (existing != null && existing.sha512().equals(wanted)) {
            return new SyncAction(ActionKind.KEEP, path, entry, existing, "up to date");
        }

        // Protection wins over a manifest instruction: the user's explicit keep rule is a
        // stronger signal than a remote server's opinion about their own client mods.
        if (existing != null && keepRules.isProtected(path)) {
            return new SyncAction(ActionKind.PROTECTED, path, entry, existing,
                keepRules.reasonFor(path));
        }

        boolean cached = wanted != null && cache.contains(wanted);
        if (existing == null) {
            if (cached) {
                return new SyncAction(ActionKind.RESTORE, path, entry, null,
                    "already downloaded");
            }
            if (entry.urls().isEmpty()) {
                return new SyncAction(ActionKind.BLOCKED, path, entry, null,
                    "no download URL and not in the cache");
            }
            return new SyncAction(ActionKind.INSTALL, path, entry, null, "missing");
        }

        if (cached) {
            return new SyncAction(ActionKind.RESTORE, path, entry, existing,
                "wrong version; correct one is already downloaded");
        }
        if (entry.urls().isEmpty()) {
            return new SyncAction(ActionKind.BLOCKED, path, entry, existing,
                "wrong version and no download URL");
        }
        return new SyncAction(ActionKind.REPLACE, path, entry, existing, "out of date");
    }

    /**
     * The roots the manifest actually touches. Quarantine is confined to these so a pack
     * that only manages {@code mods/} cannot sweep the user's {@code shaderpacks/}.
     */
    private static Set<String> managedRoots(List<ManifestEntry> entries) {
        Set<String> roots = new TreeSet<>();
        for (ManifestEntry e : entries) {
            String root = rootOf(e.path());
            if (!root.isEmpty()) {
                roots.add(root);
            }
        }
        return roots;
    }

    static String rootOf(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}

package net.frsprojects.modsync.core;

import net.frsprojects.modsync.core.apply.Journal;
import net.frsprojects.modsync.core.apply.JournalApplier;
import net.frsprojects.modsync.core.diff.ActionKind;
import net.frsprojects.modsync.core.diff.Differ;
import net.frsprojects.modsync.core.diff.FileStateCache;
import net.frsprojects.modsync.core.diff.KeepRules;
import net.frsprojects.modsync.core.diff.LocalScanner;
import net.frsprojects.modsync.core.diff.SyncAction;
import net.frsprojects.modsync.core.diff.SyncPlan;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Policy;
import net.frsprojects.modsync.core.manifest.SyncManifest;
import net.frsprojects.modsync.core.profile.ContentCache;
import net.frsprojects.modsync.core.profile.ModSyncPaths;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole loader-independent pipeline in one place: scan the game directory, diff it
 * against a manifest, journal the accepted actions, and apply them.
 *
 * <p>This is the test that would catch an ordering mistake between quarantine and install,
 * which unit tests of either half would both pass.
 */
class SyncEndToEndTest {

    @TempDir
    Path gameDir;

    private ModSyncPaths paths;
    private ContentCache cache;

    @BeforeEach
    void setUp() throws IOException {
        paths = new ModSyncPaths(gameDir);
        paths.createDirectories();
        cache = new ContentCache(paths);
    }

    /** Stands in for the download step, which has its own tests. */
    private void pretendDownloaded(String content) throws IOException {
        Path tmp = Files.createTempFile(paths.downloadTemp(), "b", ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        cache.store(tmp, TestFixtures.sha512Of(content));
    }

    private SyncPlan planFor(SyncManifest manifest, KeepRules keep) throws IOException {
        var local = new LocalScanner(gameDir, new FileStateCache())
            .scan(Set.of("mods", "config", "resourcepacks"));
        return new Differ(cache, keep).diff(manifest, manifest.files(), local);
    }

    private void run(SyncPlan plan, Set<String> accepted) throws IOException {
        Journal journal = plan.toJournal(accepted, "turbo-smp", paths);
        journal.writeTo(paths.journal());
        new JournalApplier(paths).applyPending();
    }

    @Test
    void aFullUpdateInstallsReplacesAndQuarantinesInOnePass() throws IOException {
        // Starting state: an outdated mod, a mod the pack dropped, and the player's own.
        TestFixtures.writeFile(gameDir, "mods/sodium-0.6.12.jar", "sodium-old");
        TestFixtures.writeFile(gameDir, "mods/dropped.jar", "no longer wanted");
        TestFixtures.writeFile(gameDir, "mods/iris-1.8.0.jar", "my shaders");

        List<ManifestEntry> entries = List.of(
            TestFixtures.entry("mods/sodium-0.6.13.jar", "sodium-new", Policy.REQUIRE),
            TestFixtures.entry("mods/lithium.jar", "lithium", Policy.REQUIRE));
        SyncManifest manifest = TestFixtures.manifest(entries);

        pretendDownloaded("sodium-new");
        pretendDownloaded("lithium");

        SyncPlan plan = planFor(manifest, KeepRules.of(List.of("mods/iris-*.jar")));
        assertTrue(plan.canProceed());
        run(plan, plan.defaultSelection());

        // The pack's files are in place...
        assertEquals("sodium-new", Files.readString(gameDir.resolve("mods/sodium-0.6.13.jar")));
        assertEquals("lithium", Files.readString(gameDir.resolve("mods/lithium.jar")));
        // ...the superseded and dropped ones are gone from mods/...
        assertFalse(Files.exists(gameDir.resolve("mods/sodium-0.6.12.jar")));
        assertFalse(Files.exists(gameDir.resolve("mods/dropped.jar")));
        // ...the player's own mod is untouched...
        assertEquals("my shaders", Files.readString(gameDir.resolve("mods/iris-1.8.0.jar")));
        // ...and nothing was destroyed.
        Path quarantine = paths.quarantineDir("turbo-smp").resolve("mods");
        assertEquals("sodium-old", Files.readString(quarantine.resolve("sodium-0.6.12.jar")));
        assertEquals("no longer wanted", Files.readString(quarantine.resolve("dropped.jar")));
    }

    /** Replacing a file at the same path must quarantine before it links, not after. */
    @Test
    void replacingInPlacePreservesTheOldContent() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/a.jar", "version-1");
        SyncManifest manifest = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/a.jar", "version-2", Policy.REQUIRE)));

        // Real order: diff first (nothing cached yet, so this is a REPLACE), download
        // second, apply third.
        SyncPlan plan = planFor(manifest, KeepRules.defaults());
        assertEquals(ActionKind.REPLACE, plan.actions().get(0).kind());
        pretendDownloaded("version-2");
        run(plan, plan.defaultSelection());

        assertEquals("version-2", Files.readString(gameDir.resolve("mods/a.jar")));
        assertEquals("version-1", Files.readString(
            paths.quarantineDir("turbo-smp").resolve("mods/a.jar")));
    }

    @Test
    void decliningAnOptionalEntryLeavesItUninstalled() throws IOException {
        SyncManifest manifest = TestFixtures.manifest(List.of(
            TestFixtures.entry("mods/required.jar", "req", Policy.REQUIRE),
            TestFixtures.entry("mods/extra.jar", "opt", Policy.OPTIONAL)));
        pretendDownloaded("req");
        pretendDownloaded("opt");

        SyncPlan plan = planFor(manifest, KeepRules.defaults());
        // defaultSelection excludes OPTIONAL, which is what declining looks like.
        run(plan, plan.defaultSelection());

        assertTrue(Files.exists(gameDir.resolve("mods/required.jar")));
        assertFalse(Files.exists(gameDir.resolve("mods/extra.jar")));
    }

    @Test
    void acceptingAnOptionalEntryInstallsIt() throws IOException {
        SyncManifest manifest = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/extra.jar", "opt", Policy.OPTIONAL)));
        pretendDownloaded("opt");

        SyncPlan plan = planFor(manifest, KeepRules.defaults());
        run(plan, Set.of("mods/extra.jar"));

        assertEquals("opt", Files.readString(gameDir.resolve("mods/extra.jar")));
    }

    /** Rejoining an unchanged server must be a complete no-op. */
    @Test
    void asecondSyncAgainstTheSameManifestChangesNothing() throws IOException {
        SyncManifest manifest = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/a.jar", "content", Policy.REQUIRE)));
        pretendDownloaded("content");

        SyncPlan first = planFor(manifest, KeepRules.defaults());
        run(first, first.defaultSelection());

        SyncPlan second = planFor(manifest, KeepRules.defaults());
        assertTrue(second.isUpToDate(), "rejoining an unchanged pack must need no work");
        assertTrue(second.toJournal(second.defaultSelection(), "turbo-smp", paths).isEmpty());
    }

    /** Switching servers must not re-download anything both packs share. */
    @Test
    void switchingBetweenPacksReusesTheCache() throws IOException {
        SyncManifest packA = TestFixtures.manifest(List.of(
            TestFixtures.entry("mods/shared.jar", "shared", Policy.REQUIRE),
            TestFixtures.entry("mods/only-a.jar", "a-only", Policy.REQUIRE)));
        pretendDownloaded("shared");
        pretendDownloaded("a-only");

        SyncPlan planA = planFor(packA, KeepRules.defaults());
        run(planA, planA.defaultSelection());

        SyncManifest packB = TestFixtures.manifest(List.of(
            TestFixtures.entry("mods/shared.jar", "shared", Policy.REQUIRE),
            TestFixtures.entry("mods/only-b.jar", "b-only", Policy.REQUIRE)));
        pretendDownloaded("b-only");

        SyncPlan planB = planFor(packB, KeepRules.defaults());
        var kinds = planB.actions().stream()
            .collect(Collectors.toMap(SyncAction::path, SyncAction::kind));

        assertEquals(ActionKind.KEEP, kinds.get("mods/shared.jar"), "shared mod stays put");
        assertEquals(ActionKind.RESTORE, kinds.get("mods/only-b.jar"), "served from cache");
        assertEquals(0L, planB.downloadBytes(), "switching packs must need no network");

        run(planB, planB.defaultSelection());
        assertEquals("b-only", Files.readString(gameDir.resolve("mods/only-b.jar")));
        assertFalse(Files.exists(gameDir.resolve("mods/only-a.jar")));

        // Going back to pack A is likewise free: only-a.jar is still in the cache.
        SyncPlan backToA = planFor(packA, KeepRules.defaults());
        assertEquals(0L, backToA.downloadBytes());
    }

    @Test
    void aForbiddenModIsRemovedFromModsButKeptInQuarantine() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/cheat.jar", "cheaty");
        SyncManifest manifest = TestFixtures.manifest(List.of(
            TestFixtures.entry("mods/cheat.jar", "cheaty", Policy.FORBID),
            TestFixtures.entry("mods/ok.jar", "fine", Policy.REQUIRE)));
        pretendDownloaded("fine");

        SyncPlan plan = planFor(manifest, KeepRules.defaults());
        run(plan, plan.defaultSelection());

        assertFalse(Files.exists(gameDir.resolve("mods/cheat.jar")));
        assertEquals("cheaty", Files.readString(
            paths.quarantineDir("turbo-smp").resolve("mods/cheat.jar")));
    }
}

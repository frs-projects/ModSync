package net.frsprojects.modsync.core.sync;

import net.frsprojects.modsync.core.TestFixtures;
import net.frsprojects.modsync.core.apply.JournalOp;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalChoicesTest {

    @TempDir
    Path gameDir;

    private ModSyncPaths paths;

    @BeforeEach
    void setUp() throws IOException {
        paths = new ModSyncPaths(gameDir);
        paths.createDirectories();
    }

    private SyncPlan plan(ManifestEntry... entries) {
        SyncManifest m = TestFixtures.manifest(List.of(entries));
        return new Differ(new ContentCache(paths), KeepRules.defaults())
            .diff(m, m.files(), List.of());
    }

    private static List<String> paths(List<SyncAction> actions) {
        return actions.stream().map(SyncAction::path).toList();
    }

    @Test
    void recommendedStartsTickedAndOptionalDoesNot() {
        SyncPlan plan = plan(
            TestFixtures.entry("mods/req.jar", "r", Policy.REQUIRE),
            TestFixtures.entry("mods/rec.jar", "c", Policy.RECOMMEND),
            TestFixtures.entry("mods/opt.jar", "o", Policy.OPTIONAL));
        OptionalChoices choices = OptionalChoices.empty();

        assertEquals(Set.of("mods/req.jar", "mods/rec.jar"), choices.initialSelection(plan));
        assertEquals(List.of("mods/rec.jar"), paths(choices.undecided(plan, Policy.RECOMMEND)));
        assertEquals(List.of("mods/opt.jar"), paths(choices.undecided(plan, Policy.OPTIONAL)));
    }

    @Test
    void aPlainAnswerIsRememberedButStillAsked() {
        ManifestEntry opt = TestFixtures.entry("mods/opt.jar", "o", Policy.OPTIONAL);
        SyncPlan plan = plan(opt);
        OptionalChoices choices = OptionalChoices.empty();
        choices.record(opt, true, false);

        assertEquals(Set.of("mods/opt.jar"), choices.initialSelection(plan));
        assertEquals(1, choices.undecided(plan, Policy.OPTIONAL).size());
    }

    @Test
    void dontAskAgainSettlesTheChoiceUntilTheFileChanges() throws IOException {
        ManifestEntry rec = TestFixtures.entry("mods/rec.jar", "v1", Policy.RECOMMEND);
        OptionalChoices choices = OptionalChoices.empty();
        choices.record(rec, false, true);
        Path file = paths.optionalChoices("test-pack");
        choices.save(file);

        OptionalChoices loaded = OptionalChoices.load(file);
        SyncPlan same = plan(rec);
        assertTrue(loaded.undecided(same, Policy.RECOMMEND).isEmpty());
        assertTrue(loaded.initialSelection(same).isEmpty());

        // Same path, new content: the server changed what the player declined, so ask again,
        // starting from the earlier answer rather than the pack's default.
        SyncPlan changed = plan(TestFixtures.entry("mods/rec.jar", "v2", Policy.RECOMMEND));
        assertEquals(1, loaded.undecided(changed, Policy.RECOMMEND).size());
        assertTrue(loaded.initialSelection(changed).isEmpty());
    }

    @Test
    void aCorruptFileMeansNothingWasDecided() throws IOException {
        Path file = paths.optionalChoices("test-pack");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ not json");

        SyncPlan plan = plan(TestFixtures.entry("mods/opt.jar", "o", Policy.OPTIONAL));
        assertEquals(1, OptionalChoices.load(file).undecided(plan, Policy.OPTIONAL).size());
    }

    private SyncPlan installedPlan(OptionalChoices choices, KeepRules keep,
            ManifestEntry... entries) throws IOException {
        SyncManifest m = TestFixtures.manifest(List.of(entries));
        var local = new LocalScanner(gameDir, new FileStateCache()).scan(Set.of("mods"));
        return new Differ(new ContentCache(paths), keep)
            .diff(m, m.files(), local, choices.declined());
    }

    @Test
    void removingAnInstalledOptionalFileQuarantinesItOnTheNextSync() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/opt.jar", "o");
        ManifestEntry opt = TestFixtures.entry("mods/opt.jar", "o", Policy.OPTIONAL);
        OptionalChoices choices = OptionalChoices.empty();
        choices.record(opt, true, false);

        SyncPlan kept = installedPlan(choices, KeepRules.defaults(), opt);
        assertTrue(kept.isUpToDate());

        assertTrue(choices.decline(opt.identity()));
        SyncPlan plan = installedPlan(choices, KeepRules.defaults(), opt);

        SyncAction action = plan.actions().get(0);
        assertEquals(ActionKind.QUARANTINE_DECLINED, action.kind());
        // A removal is not an offer: it never appears on a choice page, and it is ticked.
        assertTrue(choices.undecided(plan, Policy.OPTIONAL).isEmpty());
        Set<String> selection = choices.initialSelection(plan);
        assertEquals(Set.of("mods/opt.jar"), selection);
        assertTrue(plan.toJournal(selection, "test-pack", paths).ops().stream()
            .anyMatch(op -> op.kind() == JournalOp.Kind.MOVE));
    }

    @Test
    void aKeepRuleBeatsARemoval() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/opt.jar", "o");
        ManifestEntry opt = TestFixtures.entry("mods/opt.jar", "o", Policy.OPTIONAL);
        OptionalChoices choices = OptionalChoices.empty();
        choices.record(opt, false, true);

        SyncPlan plan = installedPlan(choices, KeepRules.of(List.of("mods/opt.jar")), opt);
        assertEquals(ActionKind.PROTECTED, plan.actions().get(0).kind());
    }

    @Test
    void forgettingAChoiceOffersTheFileAgain() {
        ManifestEntry opt = TestFixtures.entry("mods/opt.jar", "o", Policy.OPTIONAL);
        OptionalChoices choices = OptionalChoices.empty();
        choices.record(opt, false, true);
        assertTrue(choices.undecided(plan(opt), Policy.OPTIONAL).isEmpty());

        assertTrue(choices.forget(opt.identity()));
        assertFalse(choices.forget(opt.identity()));
        assertEquals(1, choices.undecided(plan(opt), Policy.OPTIONAL).size());
    }
}

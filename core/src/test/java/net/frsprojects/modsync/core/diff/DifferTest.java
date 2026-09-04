package net.frsprojects.modsync.core.diff;

import net.frsprojects.modsync.core.TestFixtures;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Policy;
import net.frsprojects.modsync.core.manifest.SyncManifest;
import net.frsprojects.modsync.core.manifest.UnlistedPolicy;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DifferTest {

    @TempDir
    Path gameDir;

    private ModSyncPaths paths;
    private ContentCache cache;
    private FileStateCache stateCache;

    @BeforeEach
    void setUp() throws IOException {
        paths = new ModSyncPaths(gameDir);
        paths.createDirectories();
        cache = new ContentCache(paths);
        stateCache = new FileStateCache();
    }

    private List<LocalFile> scan() throws IOException {
        return new LocalScanner(gameDir, stateCache)
            .scan(java.util.Set.of("mods", "config", "resourcepacks"));
    }

    private Map<String, SyncAction> diff(SyncManifest manifest, KeepRules keep)
            throws IOException {
        SyncPlan plan = new Differ(cache, keep).diff(manifest, manifest.files(), scan());
        return plan.actions().stream()
            .collect(Collectors.toMap(SyncAction::path, Function.identity()));
    }

    private SyncPlan plan(SyncManifest manifest, KeepRules keep) throws IOException {
        return new Differ(cache, keep).diff(manifest, manifest.files(), scan());
    }

    @Test
    void aMatchingFileIsKept() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/a.jar", "content-a");
        SyncManifest m = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/a.jar", "content-a", Policy.REQUIRE)));

        assertEquals(ActionKind.KEEP, diff(m, KeepRules.defaults()).get("mods/a.jar").kind());
        assertTrue(plan(m, KeepRules.defaults()).isUpToDate());
    }

    @Test
    void aMissingFileIsInstalled() throws IOException {
        SyncManifest m = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/a.jar", "content-a", Policy.REQUIRE)));
        assertEquals(ActionKind.INSTALL, diff(m, KeepRules.defaults()).get("mods/a.jar").kind());
    }

    @Test
    void aStaleFileIsReplaced() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/a.jar", "old-content");
        SyncManifest m = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/a.jar", "new-content", Policy.REQUIRE)));
        assertEquals(ActionKind.REPLACE, diff(m, KeepRules.defaults()).get("mods/a.jar").kind());
    }

    @Test
    void anAlreadyCachedFileNeedsNoDownload() throws IOException {
        String sha = TestFixtures.sha512Of("content-a");
        Path blob = Files.createTempFile(paths.downloadTemp(), "x", ".tmp");
        Files.writeString(blob, "content-a", StandardCharsets.UTF_8);
        cache.store(blob, sha);

        SyncManifest m = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/a.jar", "content-a", Policy.REQUIRE)));
        SyncPlan p = plan(m, KeepRules.defaults());

        assertEquals(ActionKind.RESTORE, p.actions().get(0).kind());
        assertEquals(0L, p.downloadBytes(), "restore must not require the network");
    }

    /**
     * The whitelist model's whole point: a version bump needs no install-state tracking,
     * because the old jar simply becomes unlisted.
     */
    @Test
    void aVersionBumpQuarantinesTheOldJarAndInstallsTheNew() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/sodium-0.6.12.jar", "sodium-old");
        SyncManifest m = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/sodium-0.6.13.jar", "sodium-new", Policy.REQUIRE)));

        Map<String, SyncAction> actions = diff(m, KeepRules.defaults());
        assertEquals(ActionKind.INSTALL, actions.get("mods/sodium-0.6.13.jar").kind());
        assertEquals(ActionKind.QUARANTINE_UNLISTED,
            actions.get("mods/sodium-0.6.12.jar").kind());
    }

    @Test
    void aForbiddenFileIsQuarantined() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/cheat.jar", "cheaty");
        SyncManifest m = TestFixtures.manifest(List.of(
            TestFixtures.entry("mods/cheat.jar", "cheaty", Policy.FORBID),
            TestFixtures.entry("mods/ok.jar", "fine", Policy.REQUIRE)));

        assertEquals(ActionKind.QUARANTINE_FORBIDDEN,
            diff(m, KeepRules.defaults()).get("mods/cheat.jar").kind());
    }

    @Test
    void aForbiddenFileThatIsAbsentIsANoop() throws IOException {
        SyncManifest m = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/cheat.jar", "cheaty", Policy.FORBID)));
        assertEquals(ActionKind.KEEP, diff(m, KeepRules.defaults()).get("mods/cheat.jar").kind());
    }

    @Test
    void unlistedFilesSurviveWhenThePackSaysKeep() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/mine.jar", "personal");
        SyncManifest m = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/a.jar", "content-a", Policy.REQUIRE)),
            UnlistedPolicy.KEEP);

        assertFalse(diff(m, KeepRules.defaults()).containsKey("mods/mine.jar"));
    }

    /** Without this, the first sync would sweep away the player's own client-side mods. */
    @Test
    void alwaysKeepGlobsProtectPersonalMods() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/iris-1.8.0.jar", "shaders");
        TestFixtures.writeFile(gameDir, "mods/random.jar", "whatever");
        SyncManifest m = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/a.jar", "content-a", Policy.REQUIRE)));

        Map<String, SyncAction> actions = diff(m, KeepRules.of(List.of("mods/iris-*.jar")));
        assertEquals(ActionKind.PROTECTED, actions.get("mods/iris-1.8.0.jar").kind());
        assertEquals(ActionKind.QUARANTINE_UNLISTED, actions.get("mods/random.jar").kind());
    }

    /** Losing ModSync itself mid-sync is unrecoverable from inside the game. */
    @Test
    void modSyncNeverQuarantinesItself() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/modsync-0.1.0+1.21.1-neoforge.jar", "me");
        SyncManifest m = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/a.jar", "content-a", Policy.REQUIRE)));

        assertEquals(ActionKind.PROTECTED,
            diff(m, KeepRules.defaults()).get("mods/modsync-0.1.0+1.21.1-neoforge.jar").kind());
    }

    @Test
    void quarantineIsConfinedToRootsTheManifestActuallyTouches() throws IOException {
        TestFixtures.writeFile(gameDir, "resourcepacks/mine.zip", "my pack");
        // The manifest only manages mods/, so resourcepacks/ must be left entirely alone.
        SyncManifest m = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/a.jar", "content-a", Policy.REQUIRE)));

        assertFalse(diff(m, KeepRules.defaults()).containsKey("resourcepacks/mine.zip"));
    }

    @Test
    void anEntryWithNoUrlAndNoCacheBlocksTheJoin() throws IOException {
        SyncManifest m = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/a.jar", "content-a", Policy.REQUIRE, List.of())));
        SyncPlan p = plan(m, KeepRules.defaults());

        assertEquals(ActionKind.BLOCKED, p.actions().get(0).kind());
        assertFalse(p.canProceed());
        assertEquals(1, p.blocked().size());
    }

    @Test
    void anOptionalEntryWithNoUrlDoesNotBlockTheJoin() throws IOException {
        SyncManifest m = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/a.jar", "content-a", Policy.OPTIONAL, List.of())));
        assertTrue(plan(m, KeepRules.defaults()).canProceed());
    }

    @Test
    void defaultSelectionTicksRequiredAndRecommendedButNotOptional() throws IOException {
        SyncManifest m = TestFixtures.manifest(List.of(
            TestFixtures.entry("mods/req.jar", "r", Policy.REQUIRE),
            TestFixtures.entry("mods/rec.jar", "c", Policy.RECOMMEND),
            TestFixtures.entry("mods/opt.jar", "o", Policy.OPTIONAL)));

        var selected = plan(m, KeepRules.defaults()).defaultSelection();
        assertTrue(selected.contains("mods/req.jar"));
        assertTrue(selected.contains("mods/rec.jar"));
        assertFalse(selected.contains("mods/opt.jar"));
    }

    @Test
    void downloadBytesCountsOnlyWhatMustBeFetched() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/have.jar", "have-it");
        SyncManifest m = TestFixtures.manifest(List.of(
            TestFixtures.entry("mods/have.jar", "have-it", Policy.REQUIRE),
            TestFixtures.entry("mods/need.jar", "0123456789", Policy.REQUIRE)));

        assertEquals(10L, plan(m, KeepRules.defaults()).downloadBytes());
    }

    /** A user keep rule is a stronger signal than a remote server's opinion. */
    @Test
    void aKeepRuleOverridesAManifestReplacement() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/iris-1.8.0.jar", "my version");
        SyncManifest m = TestFixtures.manifest(
            List.of(TestFixtures.entry("mods/iris-1.8.0.jar", "their version", Policy.REQUIRE)));

        assertEquals(ActionKind.PROTECTED,
            diff(m, KeepRules.of(List.of("mods/iris-*.jar"))).get("mods/iris-1.8.0.jar").kind());
    }
}

package net.frsprojects.modsync.core.apply;

import net.frsprojects.modsync.core.TestFixtures;
import net.frsprojects.modsync.core.profile.ContentCache;
import net.frsprojects.modsync.core.profile.ModSyncPaths;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalApplierTest {

    @TempDir
    Path gameDir;

    private ModSyncPaths paths;
    private ContentCache cache;
    private JournalApplier applier;

    @BeforeEach
    void setUp() throws IOException {
        paths = new ModSyncPaths(gameDir);
        paths.createDirectories();
        cache = new ContentCache(paths);
        applier = new JournalApplier(paths);
    }

    private String cacheContent(String content) throws IOException {
        String sha = TestFixtures.sha512Of(content);
        Path tmp = Files.createTempFile(paths.downloadTemp(), "blob", ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        cache.store(tmp, sha);
        return sha;
    }

    @Test
    void linkPlacesCachedContent() throws IOException {
        String sha = cacheContent("hello");
        applier.apply(new Journal(List.of(
            JournalOp.mkdir("mods"),
            JournalOp.link(sha, "mods/a.jar"))));

        assertEquals("hello", Files.readString(gameDir.resolve("mods/a.jar")));
    }

    @Test
    void moveRelocatesAFile() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/old.jar", "stale");
        applier.apply(new Journal(List.of(
            JournalOp.mkdir("modsync/quarantine/p/mods"),
            JournalOp.move("mods/old.jar", "modsync/quarantine/p/mods/old.jar"))));

        assertFalse(Files.exists(gameDir.resolve("mods/old.jar")));
        assertEquals("stale",
            Files.readString(gameDir.resolve("modsync/quarantine/p/mods/old.jar")));
    }

    /**
     * The crash-recovery contract: the applier can die at any point and be re-run from the
     * top, because replaying the whole journal is the recovery mechanism. This drives a kill
     * between every pair of operations and asserts the end state is identical each time.
     */
    @Test
    void replayingFromAnyPointReachesTheSameEndState() throws IOException {
        String shaNew = cacheContent("new-content");
        Journal journal = new Journal(List.of(
            JournalOp.mkdir("modsync/quarantine/p/mods"),
            JournalOp.move("mods/a.jar", "modsync/quarantine/p/mods/a.jar"),
            JournalOp.mkdir("mods"),
            JournalOp.link(shaNew, "mods/a.jar")));

        for (int stopAfter = 0; stopAfter <= journal.ops().size(); stopAfter++) {
            resetGameDir();
            TestFixtures.writeFile(gameDir, "mods/a.jar", "old-content");

            // Simulate a crash: run only the first `stopAfter` operations...
            applier.apply(new Journal(journal.ops().subList(0, stopAfter)));
            // ...then recover by replaying the entire journal.
            applier.apply(journal);

            assertEquals("new-content", Files.readString(gameDir.resolve("mods/a.jar")),
                "crash after " + stopAfter + " op(s) must still land the new content");
            assertEquals("old-content",
                Files.readString(gameDir.resolve("modsync/quarantine/p/mods/a.jar")),
                "crash after " + stopAfter + " op(s) must still preserve the old content");
        }
    }

    @Test
    void reapplyingACompletedJournalChangesNothing() throws IOException {
        String sha = cacheContent("hello");
        Journal journal = new Journal(List.of(
            JournalOp.mkdir("mods"),
            JournalOp.link(sha, "mods/a.jar")));

        assertEquals(2, applier.apply(journal).applied());
        JournalApplier.Result second = applier.apply(journal);
        assertEquals(0, second.applied(), "a second run must be a no-op");
        assertEquals(2, second.alreadySatisfied());
    }

    /** Quarantine must never silently destroy an earlier quarantined file. */
    @Test
    void quarantiningTwiceKeepsBothFiles() throws IOException {
        TestFixtures.writeFile(gameDir, "modsync/quarantine/p/mods/a.jar", "first");
        TestFixtures.writeFile(gameDir, "mods/a.jar", "second");

        applier.apply(new Journal(List.of(
            JournalOp.move("mods/a.jar", "modsync/quarantine/p/mods/a.jar"))));

        Path dir = gameDir.resolve("modsync/quarantine/p/mods");
        List<String> contents = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                contents.add(Files.readString(p));
            }
        }
        assertEquals(2, contents.size(), "both quarantined files must survive");
        assertTrue(contents.contains("first"));
        assertTrue(contents.contains("second"));
    }

    @Test
    void linkFailsLoudlyWhenTheCacheIsMissingTheContent() {
        String sha = TestFixtures.sha512Of("never-cached");
        JournalException e = assertThrows(JournalException.class,
            () -> applier.apply(new Journal(List.of(JournalOp.link(sha, "mods/a.jar")))));
        assertTrue(e.getMessage().contains("missing from the cache"));
    }

    @Test
    void moveFailsLoudlyWhenNeitherSourceNorDestinationExists() {
        assertThrows(JournalException.class,
            () -> applier.apply(new Journal(List.of(JournalOp.move("mods/gone.jar", "q/gone.jar")))));
    }

    @Test
    void applyPendingConsumesAndRemovesTheJournalFile() throws IOException {
        String sha = cacheContent("hello");
        new Journal(List.of(JournalOp.mkdir("mods"), JournalOp.link(sha, "mods/a.jar")))
            .writeTo(paths.journal());

        assertEquals(2, applier.applyPending().applied());
        assertFalse(Files.exists(paths.journal()), "journal must be removed once applied");
        assertEquals("hello", Files.readString(gameDir.resolve("mods/a.jar")));
    }

    @Test
    void applyPendingIsANoopWhenThereIsNoJournal() throws IOException {
        assertEquals(0, applier.applyPending().total());
    }

    private void resetGameDir() throws IOException {
        for (String dir : List.of("mods", "modsync/quarantine")) {
            Path p = gameDir.resolve(dir);
            if (Files.exists(p)) {
                try (var walk = Files.walk(p)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                        try {
                            Files.delete(f);
                        } catch (IOException ignored) {
                            // Best effort cleanup between iterations.
                        }
                    });
                }
            }
        }
    }
}

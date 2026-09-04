package net.frsprojects.modsync.core.profile;

import net.frsprojects.modsync.core.TestFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentCacheTest {

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

    private String store(String content) throws IOException {
        String sha = TestFixtures.sha512Of(content);
        Path tmp = Files.createTempFile(paths.downloadTemp(), "b", ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        cache.store(tmp, sha);
        return sha;
    }

    @Test
    void storesAndLocatesContent() throws IOException {
        String sha = store("hello");
        assertTrue(cache.contains(sha));
        assertNotNull(cache.locate(sha));
        assertEquals("hello", Files.readString(cache.locate(sha)));
    }

    @Test
    void unknownContentIsNotLocated() {
        assertNull(cache.locate(TestFixtures.sha512Of("never stored")));
    }

    /** The point of content addressing: five servers wanting one mod cost one copy. */
    @Test
    void identicalContentIsStoredOnlyOnce() throws IOException {
        String first = store("shared-mod");
        String second = store("shared-mod");
        assertEquals(first, second);

        long files;
        try (var walk = Files.walk(paths.cacheRoot())) {
            files = walk.filter(Files::isRegularFile).count();
        }
        assertEquals(1L, files);
    }

    @Test
    void materialiseProducesTheRightContentWhicheverStrategyIsUsed() throws IOException {
        String sha = store("payload");
        Path dest = gameDir.resolve("mods/a.jar");

        // The return value says whether a hard link was possible; both outcomes are valid,
        // and callers must not depend on which one they got.
        boolean linked = cache.materialise(sha, dest);
        assertEquals("payload", Files.readString(dest));
        assertTrue(linked || Files.isRegularFile(dest));
    }

    @Test
    void materialiseOverwritesAnExistingFile() throws IOException {
        String sha = store("new");
        TestFixtures.writeFile(gameDir, "mods/a.jar", "old");
        cache.materialise(sha, gameDir.resolve("mods/a.jar"));
        assertEquals("new", Files.readString(gameDir.resolve("mods/a.jar")));
    }

    @Test
    void materialiseFailsWhenContentIsNotCached() {
        assertThrows(IOException.class, () ->
            cache.materialise(TestFixtures.sha512Of("absent"), gameDir.resolve("mods/a.jar")));
    }

    @Test
    void adoptCopiesAnExistingGameFileWithoutMovingIt() throws IOException {
        String sha = TestFixtures.writeFile(gameDir, "mods/mine.jar", "my content");
        cache.adopt(gameDir.resolve("mods/mine.jar"), sha);

        assertTrue(cache.contains(sha));
        assertTrue(Files.exists(gameDir.resolve("mods/mine.jar")), "original must stay put");
    }

    @Test
    void pruneRemovesOnlyUnreferencedBlobs() throws IOException {
        String keep = store("keep me");
        String drop = store("drop me");

        long freed = cache.prune(Set.of(keep));

        assertTrue(cache.contains(keep));
        assertFalse(cache.contains(drop));
        assertEquals("drop me".length(), freed);
    }

    @Test
    void verifyDeletesABlobWhoseContentNoLongerMatchesItsName() throws IOException {
        String sha = store("original");
        assertTrue(cache.verify(sha));

        // Simulate bit rot or a half-written file surviving a crash.
        Files.writeString(paths.cacheEntry(sha), "corrupted", StandardCharsets.UTF_8);

        assertFalse(cache.verify(sha));
        assertFalse(cache.contains(sha), "a corrupt blob must not stay in the cache");
    }

    @Test
    void cachePathsFanOutByHashPrefix() {
        String sha = "ab" + "c".repeat(126);
        assertEquals("ab", paths.cacheEntry(sha).getParent().getFileName().toString());
    }
}

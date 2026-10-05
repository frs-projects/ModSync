package net.frsprojects.modsync.core.sync;

import net.frsprojects.modsync.core.TestFixtures;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Policy;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Picking up files the player downloaded in a browser. */
class ManualImportsTest {

    @TempDir
    Path gameDir;

    @TempDir
    Path downloads;

    private ModSyncPaths paths;
    private ContentCache cache;

    @BeforeEach
    void setUp() throws IOException {
        paths = new ModSyncPaths(gameDir);
        paths.createDirectories();
        cache = new ContentCache(paths);
    }

    private Path download(String name, String content) throws IOException {
        Path p = downloads.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void takesAMatchingFileIntoTheCacheAndLeavesTheOriginal() throws IOException {
        ManifestEntry jei = TestFixtures.manualEntry("mods/jei.jar", "jei-bytes", Policy.REQUIRE);
        ManualImports imports = new ManualImports(paths, List.of(jei));
        // Browsers rename a second download, so the name must not matter.
        Path original = download("jei (1).jar", "jei-bytes");

        assertEquals(List.of(jei), imports.scan(List.of(downloads)));
        assertTrue(cache.contains(TestFixtures.sha512Of("jei-bytes")));
        assertTrue(imports.pending().isEmpty());
        assertTrue(imports.isDone(jei));
        assertTrue(Files.exists(original), "nothing outside the game directory is deleted");
    }

    @Test
    void ignoresFilesWithTheRightSizeButWrongContent() throws IOException {
        ManifestEntry a = TestFixtures.manualEntry("mods/a.jar", "aaaa", Policy.REQUIRE);
        ManualImports imports = new ManualImports(paths, List.of(a));
        download("a.jar", "bbbb");

        assertTrue(imports.scan(List.of(downloads)).isEmpty());
        assertFalse(cache.contains(TestFixtures.sha512Of("aaaa")));
        assertEquals(List.of(a), imports.pending());
        try (var tmp = Files.list(paths.downloadTemp())) {
            assertEquals(0, tmp.count(), "a rejected copy is cleaned up");
        }
    }

    @Test
    void skipsDownloadsThatAreStillInProgress() throws IOException {
        ManifestEntry a = TestFixtures.manualEntry("mods/a.jar", "aaaa", Policy.REQUIRE);
        ManualImports imports = new ManualImports(paths, List.of(a));
        download("a.jar.crdownload", "aaaa");
        download("a.jar.part", "aaaa");

        assertTrue(imports.scan(List.of(downloads)).isEmpty());
    }

    @Test
    void picksUpAFileThatArrivesBetweenPolls() throws IOException {
        ManifestEntry a = TestFixtures.manualEntry("mods/a.jar", "aaaa", Policy.REQUIRE);
        ManifestEntry b = TestFixtures.manualEntry("mods/b.jar", "bbbbbb", Policy.REQUIRE);
        ManualImports imports = new ManualImports(paths, List.of(a, b));

        download("a.jar", "aaaa");
        assertEquals(List.of(a), imports.scan(List.of(downloads)));
        assertEquals(List.of(b), imports.pending());

        download("b.jar", "bbbbbb");
        assertEquals(List.of(b), imports.scan(List.of(downloads)));
        assertTrue(imports.pending().isEmpty());
    }

    @Test
    void aRejectedFileIsReconsideredOnceItChanges() throws IOException {
        ManifestEntry a = TestFixtures.manualEntry("mods/a.jar", "aaaa", Policy.REQUIRE);
        ManualImports imports = new ManualImports(paths, List.of(a));
        Path file = download("a.jar", "xxxx");
        assertTrue(imports.scan(List.of(downloads)).isEmpty());

        Files.writeString(file, "aaaa", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
            Files.getLastModifiedTime(file).toMillis() + 5_000));
        assertEquals(List.of(a), imports.scan(List.of(downloads)));
    }

    @Test
    void acceptsDroppedFilesFromAnywhere() throws IOException {
        ManifestEntry a = TestFixtures.manualEntry("mods/a.jar", "aaaa", Policy.REQUIRE);
        ManualImports imports = new ManualImports(paths, List.of(a));
        Path elsewhere = Files.createDirectories(gameDir.resolve("somewhere"));
        Path dropped = Files.writeString(elsewhere.resolve("renamed.jar"), "aaaa");

        assertEquals(List.of(a), imports.offer(List.of(dropped)));
    }

    @Test
    void missingFoldersAreSkipped() {
        ManifestEntry a = TestFixtures.manualEntry("mods/a.jar", "aaaa", Policy.REQUIRE);
        ManualImports imports = new ManualImports(paths, List.of(a));
        assertTrue(imports.scan(List.of(downloads.resolve("nope"))).isEmpty());
    }
}

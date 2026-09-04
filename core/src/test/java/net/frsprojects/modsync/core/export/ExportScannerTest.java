package net.frsprojects.modsync.core.export;

import net.frsprojects.modsync.core.TestFixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportScannerTest {

    @TempDir
    Path gameDir;

    private List<String> paths(String root) throws Exception {
        return new ExportScanner(gameDir).scan(root).stream()
            .map(ExportCandidate::path)
            .collect(Collectors.toList());
    }

    @Test
    void findsFilesAndHashesThem() throws Exception {
        TestFixtures.writeFile(gameDir, "mods/sodium.jar", "sodium");
        TestFixtures.writeFile(gameDir, "mods/nested/extra.jar", "extra");

        List<ExportCandidate> found = new ExportScanner(gameDir).scan("mods");

        assertEquals(List.of("mods/nested/extra.jar", "mods/sodium.jar"),
            found.stream().map(ExportCandidate::path).collect(Collectors.toList()));
        for (ExportCandidate c : found) {
            assertNotNull(c.hashes().sha512());
            assertNotNull(c.hashes().sha1());
            assertTrue(c.size() > 0);
            assertEquals("mods", c.root());
        }
        assertEquals(TestFixtures.sha512Of("sodium"),
            found.get(1).hashes().sha512());
    }

    @Test
    void skipsDisabledModsAndDotfiles() throws Exception {
        TestFixtures.writeFile(gameDir, "mods/keep.jar", "keep");
        TestFixtures.writeFile(gameDir, "mods/off.jar.disabled", "off");
        TestFixtures.writeFile(gameDir, "mods/.index.lock", "lock");

        assertEquals(List.of("mods/keep.jar"), paths("mods"));
    }

    @Test
    void skipsSymlinks() throws Exception {
        TestFixtures.writeFile(gameDir, "mods/real.jar", "real");
        Path target = gameDir.resolve("outside.jar");
        Files.writeString(target, "outside");
        try {
            Files.createSymbolicLink(gameDir.resolve("mods/linked.jar"), target);
        } catch (IOException | UnsupportedOperationException e) {
            return; // No symlink support (unprivileged Windows); nothing to assert.
        }
        assertEquals(List.of("mods/real.jar"), paths("mods"));
    }

    @Test
    void rejectsFoldersOutsideTheAllowlist() throws Exception {
        Files.createDirectories(gameDir.resolve("saves"));
        ExportException e = assertThrows(ExportException.class, () -> paths("saves"));
        assertTrue(e.getMessage().contains("not an exportable folder"));

        // The point of the allowlist: no walking out of the game directory.
        assertThrows(ExportException.class, () -> paths(".."));
    }

    @Test
    void reportsAMissingFolderClearly() {
        ExportException e = assertThrows(ExportException.class, () -> paths("shaderpacks"));
        assertTrue(e.getMessage().contains("no 'shaderpacks' folder"));
    }

    @Test
    void allowedRootsMatchTheSandbox() {
        assertTrue(ExportScanner.allowedRoots().contains("mods"));
        assertTrue(ExportScanner.allowedRoots().contains("shaderpacks"));
        assertTrue(ExportScanner.isAllowedRoot("config"));
        assertTrue(!ExportScanner.isAllowedRoot("saves"));
    }
}

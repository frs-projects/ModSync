package net.frsprojects.modsync.core.profile;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModSyncPathsTest {

    private final ModSyncPaths paths = new ModSyncPaths(Path.of("/games/mc"));

    @Test
    void everythingLivesUnderTheModsyncDirectory() {
        assertTrue(paths.cacheRoot().startsWith(paths.root()));
        assertTrue(paths.profilesRoot().startsWith(paths.root()));
        assertTrue(paths.journal().startsWith(paths.root()));
        assertTrue(paths.quarantineDir("p").startsWith(paths.root()));
    }

    /**
     * A profile id becomes a directory name, so it must not be able to escape. Dots are
     * legal in a file name and are kept; what matters is that separators are neutralised
     * and that the bare traversal names cannot survive.
     */
    @Test
    void sanitizeNeutralisesPathSeparatorsAndTraversal() {
        assertEquals(".._etc_passwd", ModSyncPaths.sanitize("../etc/passwd"));
        assertEquals("a_b", ModSyncPaths.sanitize("a/b"));
        assertEquals("a_b", ModSyncPaths.sanitize("a\\b"));
        assertEquals("_", ModSyncPaths.sanitize(".."));
        assertEquals("_", ModSyncPaths.sanitize("."));
        assertEquals("_", ModSyncPaths.sanitize(""));
    }

    /** Whatever comes out must be a single path segment that resolves nowhere new. */
    @Test
    void sanitizeAlwaysYieldsOneHarmlessSegment() {
        for (String hostile : new String[] {
                "../etc/passwd", "..", ".", "a/b", "a\\b", "/abs", "C:/win", "..\\..\\x"}) {
            String safe = ModSyncPaths.sanitize(hostile);
            assertEquals(1, Path.of(safe).getNameCount(), "not one segment: " + safe);
            assertFalse(safe.equals("..") || safe.equals("."), "traversal survived: " + safe);
            assertTrue(paths.profileDir(hostile).normalize().startsWith(paths.profilesRoot()),
                "escaped profiles root: " + hostile);
        }
    }

    @Test
    void sanitizeKeepsOrdinaryPackIds() {
        assertEquals("turbo-smp", ModSyncPaths.sanitize("turbo-smp"));
        assertEquals("pack_1.0", ModSyncPaths.sanitize("pack_1.0"));
    }

    @Test
    void sanitizeBoundsTheLength() {
        assertEquals(64, ModSyncPaths.sanitize("x".repeat(200)).length());
    }

    @Test
    void aSanitizedProfileDirectoryCannotEscapeTheProfilesRoot() {
        assertTrue(paths.profileDir("../../evil").startsWith(paths.profilesRoot()));
        assertFalse(paths.profileDir("../../evil").normalize()
            .equals(Path.of("/games/evil")));
    }
}

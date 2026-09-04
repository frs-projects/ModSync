package net.frsprojects.modsync.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSandboxTest {

    @TempDir
    Path gameDir;

    private PathSandbox sandbox() {
        return new PathSandbox(gameDir);
    }

    @Test
    void acceptsOrdinaryModPath() throws Exception {
        Path resolved = sandbox().resolve("mods/sodium-0.6.13.jar");
        assertEquals(gameDir.resolve("mods").resolve("sodium-0.6.13.jar"), resolved);
    }

    @Test
    void acceptsNestedConfigPath() throws Exception {
        Path resolved = sandbox().resolve("config/sodium/options.json");
        assertTrue(resolved.startsWith(gameDir));
    }

    /**
     * The corpus that matters: every one of these has been a real path-traversal or
     * Windows-quirk bug in some downloader at some point.
     */
    @ParameterizedTest(name = "rejects \"{0}\"")
    @ValueSource(strings = {
        // Traversal, plain and disguised.
        "../evil.jar",
        "mods/../../evil.jar",
        "mods/./../../evil.jar",
        "mods/subdir/../../../evil.jar",
        "./mods/evil.jar",
        // Absolute and home-relative.
        "/etc/passwd",
        "/mods/evil.jar",
        "~/.bashrc",
        // Windows drive and UNC.
        "C:/Windows/System32/evil.dll",
        "C:\\Windows\\evil.dll",
        "\\\\server\\share\\evil.jar",
        // Backslash as a separator, which Windows would honour.
        "mods\\..\\..\\evil.jar",
        // Roots that are not on the allowlist.
        "evil.jar",
        "saves/world/level.dat",
        "logs/latest.log",
        "options.txt",
        "../.minecraft/launcher_profiles.json",
        // Windows reserved device names, with and without an extension.
        "mods/CON",
        "mods/con.jar",
        "mods/NUL.jar",
        "mods/COM1.jar",
        "mods/LPT9.txt",
        "mods/aux.jar",
        // Windows strips trailing dots and spaces, so these alias other files.
        "mods/evil.jar.",
        "mods/evil.jar ",
        "mods/trailing./evil.jar",
        // NTFS alternate data stream.
        "mods/evil.jar:stream",
        // Characters that are illegal or magic on Windows.
        "mods/ev*il.jar",
        "mods/ev?il.jar",
        "mods/ev\"il.jar",
        "mods/ev<il.jar",
        "mods/ev|il.jar",
        // Control characters and NUL.
        "mods/ev\u0000il.jar",
        "mods/ev\nil.jar",
        "mods/ev\til.jar",
        // Structural nonsense.
        "",
        "   ",
        "mods//evil.jar",
        "mods/",
        "mods",
    })
    void rejectsHostilePath(String path) {
        assertThrows(SandboxException.class, () -> sandbox().resolve(path),
            "should have rejected: " + path);
    }

    @Test
    void rejectsNullPath() {
        assertThrows(SandboxException.class, () -> sandbox().resolve(null));
    }

    @Test
    void rejectsPathThroughSymlinkLeavingGameDir() throws IOException {
        Path outside = Files.createTempDirectory("modsync-outside");
        Path link = gameDir.resolve("mods");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // Filesystem does not support symlinks; nothing to assert.
        }
        assertThrows(SandboxException.class, () -> sandbox().resolve("mods/evil.jar"));
    }

    @Test
    void rejectsOverwritingAnExistingSymlink() throws IOException {
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Path outside = Files.createTempFile("modsync-outside", ".jar");
        Path link = mods.resolve("linked.jar");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        assertThrows(SandboxException.class, () -> sandbox().resolve("mods/linked.jar"));
    }

    @Test
    void honoursACustomRootAllowlist() throws Exception {
        PathSandbox tight = new PathSandbox(gameDir, java.util.Set.of("mods"));
        tight.resolve("mods/ok.jar");
        assertThrows(SandboxException.class, () -> tight.resolve("config/nope.json"));
    }
}

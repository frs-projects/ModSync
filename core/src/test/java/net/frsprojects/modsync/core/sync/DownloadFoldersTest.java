package net.frsprojects.modsync.core.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DownloadFoldersTest {

    @TempDir
    Path home;

    @Test
    void windowsAndMacUseDownloadsUnderHome() {
        assertEquals(home.resolve("Downloads"),
            DownloadFolders.detect(Map.of(), home, "Windows 11"));
        assertEquals(home.resolve("Downloads"),
            DownloadFolders.detect(Map.of(), home, "Mac OS X"));
    }

    @Test
    void linuxReadsALocalisedFolderFromUserDirs() throws IOException {
        Path config = Files.createDirectories(home.resolve(".config"));
        Files.writeString(config.resolve("user-dirs.dirs"), """
            # This file is written by xdg-user-dirs-update
            XDG_DESKTOP_DIR="$HOME/Schreibtisch"
            XDG_DOWNLOAD_DIR="$HOME/Herunterladen"
            """);

        assertEquals(home.resolve("Herunterladen"),
            DownloadFolders.detect(Map.of(), home, "Linux"));
    }

    @Test
    void linuxPrefersTheEnvironmentAndHonoursXdgConfigHome() throws IOException {
        assertEquals(home.resolve("dl"),
            DownloadFolders.detect(Map.of("XDG_DOWNLOAD_DIR", "$HOME/dl"), home, "Linux"));

        Path elsewhere = Files.createDirectories(home.resolve("cfg"));
        Files.writeString(elsewhere.resolve("user-dirs.dirs"),
            "XDG_DOWNLOAD_DIR=\"/data/downloads\"\n");
        assertEquals(Path.of("/data/downloads"), DownloadFolders.detect(
            Map.of("XDG_CONFIG_HOME", elsewhere.toString()), home, "Linux"));
    }

    @Test
    void linuxFallsBackToDownloadsAndIgnoresADisabledEntry() throws IOException {
        assertEquals(home.resolve("Downloads"), DownloadFolders.detect(Map.of(), home, "Linux"));

        Path config = Files.createDirectories(home.resolve(".config"));
        // xdg-user-dirs disables a folder by pointing it at $HOME.
        Files.writeString(config.resolve("user-dirs.dirs"), "XDG_DOWNLOAD_DIR=\"$HOME/\"\n");
        assertNull(DownloadFolders.readUserDirs(config.resolve("user-dirs.dirs"), home));
        assertEquals(home.resolve("Downloads"), DownloadFolders.detect(Map.of(), home, "Linux"));
    }
}

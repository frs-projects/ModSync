package net.frsprojects.modsync.core.sync;

import net.frsprojects.modsync.core.config.ModSyncConfig;
import net.frsprojects.modsync.core.profile.ModSyncPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where to look for files the player downloaded by hand.
 *
 * <p>In order: folders from {@code downloadFolders} in the config, the platform's downloads
 * folder, and {@code modsync/import/}, which works everywhere and is the only one a dedicated
 * server uses.
 */
public final class DownloadFolders {

    private DownloadFolders() {}

    public static List<Path> forClient(ModSyncConfig config, ModSyncPaths paths) {
        Set<Path> out = new LinkedHashSet<>();
        for (String s : config.downloadFolders()) {
            out.add(expandHome(s, home()));
        }
        Path detected = detect(System.getenv(), home(), System.getProperty("os.name", ""));
        if (detected != null) {
            out.add(detected);
        }
        out.add(paths.importDir());
        return List.copyOf(normalized(out));
    }

    /**
     * The platform's downloads folder, or null when there is no home directory to start from.
     *
     * <p>On Linux and the BSDs this honours the XDG user dirs, because a localised desktop
     * names it {@code ~/Downloads} only in English. Windows and macOS keep it at
     * {@code ~/Downloads} whatever the language.
     */
    static Path detect(Map<String, String> env, Path home, String osName) {
        if (home == null) {
            return null;
        }
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win") || os.contains("mac")) {
            return home.resolve("Downloads");
        }
        String fromEnv = env.get("XDG_DOWNLOAD_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return expandHome(fromEnv, home);
        }
        String configHome = env.get("XDG_CONFIG_HOME");
        Path userDirs = (configHome != null && !configHome.isBlank()
            ? Path.of(configHome) : home.resolve(".config")).resolve("user-dirs.dirs");
        Path fromFile = readUserDirs(userDirs, home);
        return fromFile != null ? fromFile : home.resolve("Downloads");
    }

    /** Reads {@code XDG_DOWNLOAD_DIR="$HOME/Downloads"} out of a user-dirs.dirs file. */
    static Path readUserDirs(Path file, Path home) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        for (String line : lines) {
            String l = line.strip();
            if (!l.startsWith("XDG_DOWNLOAD_DIR=")) {
                continue;
            }
            String value = l.substring("XDG_DOWNLOAD_DIR=".length()).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.isEmpty()) {
                return null;
            }
            Path p = expandHome(value, home);
            // xdg-user-dirs sets a disabled entry to $HOME itself; scanning all of home
            // for a downloads folder would be a surprise.
            return p.equals(home) ? null : p;
        }
        return null;
    }

    private static Path expandHome(String raw, Path home) {
        String s = raw.strip();
        if (home != null) {
            if (s.equals("~") || s.equals("$HOME")) {
                return home;
            }
            if (s.startsWith("~/") || s.startsWith("~\\")) {
                return home.resolve(s.substring(2));
            }
            if (s.startsWith("$HOME/")) {
                return home.resolve(s.substring(6));
            }
        }
        return Path.of(s);
    }

    private static Path home() {
        String h = System.getProperty("user.home");
        return h == null || h.isBlank() ? null : Path.of(h);
    }

    private static List<Path> normalized(Set<Path> paths) {
        List<Path> out = new ArrayList<>();
        for (Path p : paths) {
            Path n = p.toAbsolutePath().normalize();
            if (!out.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }
}

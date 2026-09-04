package net.frsprojects.modsync.core.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Every path ModSync owns, derived from the game directory.
 *
 * <p>The layout keeps one content-addressed store shared by all servers, plus one profile
 * per pack. A mod used by five servers is stored once, and switching servers is a relink
 * rather than a re-download.
 */
public final class ModSyncPaths {

    /** Profile id used for the player's own mods, captured before any server touches them. */
    public static final String BASE_PROFILE = "_base";

    private final Path gameDir;
    private final Path root;

    public ModSyncPaths(Path gameDir) {
        this.gameDir = gameDir.toAbsolutePath().normalize();
        this.root = this.gameDir.resolve("modsync");
    }

    public Path gameDir() {
        return gameDir;
    }

    /** {@code .minecraft/modsync} */
    public Path root() {
        return root;
    }

    /** Content-addressed store: {@code modsync/cache/ab/abcdef...} */
    public Path cacheRoot() {
        return root.resolve("cache");
    }

    /** Where a blob with this SHA-512 lives, whether or not it exists yet. */
    public Path cacheEntry(String sha512) {
        String hex = sha512.toLowerCase(Locale.ROOT);
        // Two-character fan-out: a flat directory of thousands of files is slow to list
        // on every filesystem that matters.
        return cacheRoot().resolve(hex.substring(0, 2)).resolve(hex);
    }

    /** Scratch space for in-flight downloads, on the same filesystem as the cache so the
     *  final move into place is atomic. */
    public Path downloadTemp() {
        return root.resolve("tmp");
    }

    public Path profilesRoot() {
        return root.resolve("profiles");
    }

    public Path profileDir(String profileId) {
        return profilesRoot().resolve(sanitize(profileId));
    }

    public Path profileFile(String profileId) {
        return profileDir(profileId).resolve("profile.json");
    }

    /** Displaced files, per profile. Quarantine is always a move, never a delete. */
    public Path quarantineDir(String profileId) {
        return root.resolve("quarantine").resolve(sanitize(profileId));
    }

    /** Which profile {@code mods/} currently holds. */
    public Path activeState() {
        return root.resolve("active.json");
    }

    /** The operation journal, applied after the game exits. Line-based on purpose: the
     *  applier runs in a bare JVM with no Gson on its classpath. */
    public Path journal() {
        return root.resolve("pending.tsv");
    }

    /** Per-pack trust decisions and remembered optional selections. */
    public Path trustStore() {
        return root.resolve("trust.json");
    }

    /** Client configuration, including the alwaysKeep globs. */
    public Path config() {
        return root.resolve("modsync.json");
    }

    /**
     * Where {@code /modsync export} drops its manifests. Kept out of {@link #root()} itself so
     * an admin can hand someone the whole folder without also handing over their config.
     */
    public Path exportDir() {
        return root.resolve("exports");
    }

    /** A named file inside {@link #exportDir()}. The caller supplies the timestamped name. */
    public Path exportFile(String name) {
        return exportDir().resolve(sanitize(name));
    }

    /** Cached path -> hash records, so a rejoin does not rehash every jar. */
    public Path stateCache() {
        return root.resolve("filestate.tsv");
    }

    public void createDirectories() throws IOException {
        Files.createDirectories(cacheRoot());
        Files.createDirectories(downloadTemp());
        Files.createDirectories(profilesRoot());
    }

    /**
     * Makes a profile id safe as a single directory name. Manifest {@code packId}s are
     * already validated, but profile ids can also be derived from a server address.
     */
    public static String sanitize(String profileId) {
        StringBuilder sb = new StringBuilder(profileId.length());
        for (int i = 0; i < profileId.length(); i++) {
            char c = profileId.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String s = sb.toString();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) {
            return "_";
        }
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}

package net.frsprojects.modsync.core.diff;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Files the client refuses to quarantine no matter what a manifest says.
 *
 * <p>Without this, the first join to any server would sweep away the player's own
 * client-side mods — shaders, minimaps, input tweaks — which no server can know about, and
 * ModSync would quarantine itself and be unable to finish the job.
 */
public final class KeepRules {

    /**
     * Never touched, ever. These are matched on the file name so a renamed jar is still
     * recognised, because losing the loader or ModSync itself mid-sync is unrecoverable
     * from inside the game.
     */
    private static final List<String> ALWAYS_PROTECTED_PREFIXES = List.of(
        "modsync",
        "fabric-loader",
        "fabric-api",
        "neoforge",
        "forge");

    private final List<PathMatcher> userGlobs;

    private KeepRules(List<PathMatcher> userGlobs) {
        this.userGlobs = userGlobs;
    }

    /**
     * @param globs game-directory-relative glob patterns from the client config,
     *     e.g. {@code mods/iris-*.jar} or {@code shaderpacks/**}
     */
    public static KeepRules of(List<String> globs) {
        List<PathMatcher> matchers = new ArrayList<>(globs.size());
        for (String glob : globs) {
            if (glob == null || glob.isBlank()) {
                continue;
            }
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob.trim()));
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                // A bad pattern in user config must not break syncing; it just does nothing.
            }
        }
        return new KeepRules(List.copyOf(matchers));
    }

    public static KeepRules defaults() {
        return of(List.of());
    }

    /** True when this path must never be quarantined or replaced. */
    public boolean isProtected(String relativePath) {
        String fileName = relativePath.substring(relativePath.lastIndexOf('/') + 1)
            .toLowerCase(Locale.ROOT);
        for (String prefix : ALWAYS_PROTECTED_PREFIXES) {
            if (fileName.startsWith(prefix)) {
                return true;
            }
        }
        Path asPath = Path.of(relativePath.replace('/', java.io.File.separatorChar));
        for (PathMatcher matcher : userGlobs) {
            if (matcher.matches(asPath)) {
                return true;
            }
        }
        return false;
    }

    /** Why a path is protected, for the diff UI. */
    public String reasonFor(String relativePath) {
        String fileName = relativePath.substring(relativePath.lastIndexOf('/') + 1)
            .toLowerCase(Locale.ROOT);
        for (String prefix : ALWAYS_PROTECTED_PREFIXES) {
            if (fileName.startsWith(prefix)) {
                return "ModSync never touches " + prefix + " files";
            }
        }
        return "kept by your alwaysKeep configuration";
    }
}

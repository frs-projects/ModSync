package net.frsprojects.modsync.core.manifest;

import java.util.List;
import java.util.Locale;

/**
 * One file the server has an opinion about.
 *
 * <p>Fields are nullable as parsed; {@link ManifestCodec} normalizes and validates them,
 * so code downstream of the codec can rely on everything except {@link #desc},
 * {@link #group} and {@link #id} being non-null.
 */
public record ManifestEntry(
    /** Stable identity across versions, e.g. {@code modrinth:sodium}. Lets an update be
     *  recognised as replacing the previous jar rather than adding a second one. */
    String id,
    String label,
    String desc,
    /** Game-directory-relative destination, always with {@code /} separators. */
    String path,
    long size,
    Hashes hashes,
    /** Mirrors, tried in order. May be empty when only the server can supply the file. */
    List<String> urls,
    Policy policy,
    Side side,
    /** Loader ids this entry applies to; empty means "any". */
    List<String> loaders,
    /** Minecraft versions this entry applies to; empty means "any". */
    List<String> mcVersions,
    /** UI grouping, e.g. "Performance". */
    String group,
    boolean defaultEnabled
) {

    /** The file name this entry lands under, without directories. */
    public String fileName() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** The game-directory-relative folder this entry lands in, e.g. {@code mods}. */
    public String parentDir() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** Identity used for update detection; falls back to the destination path. */
    public String identity() {
        return id != null && !id.isBlank() ? id : path;
    }

    /** Whether this entry is relevant to a client running the given loader and version. */
    public boolean appliesTo(String loader, String mcVersion, boolean clientSide) {
        if (clientSide ? !side.appliesToClient() : !side.appliesToServer()) {
            return false;
        }
        if (!loaders.isEmpty() && !containsIgnoreCase(loaders, loader)) {
            return false;
        }
        return mcVersions.isEmpty() || containsIgnoreCase(mcVersions, mcVersion);
    }

    private static boolean containsIgnoreCase(List<String> haystack, String needle) {
        if (needle == null) {
            return false;
        }
        for (String s : haystack) {
            if (s.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "ManifestEntry[" + path + " " + policy.name().toLowerCase(Locale.ROOT) + "]";
    }
}

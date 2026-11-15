package net.frsprojects.modsync.core.manifest;

import java.util.regex.Pattern;

/**
 * A game-directory-relative glob over {@code /}-separated paths, as used by a manifest's
 * {@code managed} list.
 *
 * <p>{@code *} and {@code ?} stay within one path segment; {@code **} crosses segments, so
 * {@code config/modx/**} covers {@code config/modx/a.toml} and {@code config/modx/sub/b.toml}.
 * Matching is done here rather than with {@code PathMatcher} so a pattern means the same on
 * every platform, regardless of the native separator.
 */
public final class PathGlob {

    private final String pattern;
    private final String root;
    private final Pattern regex;

    private PathGlob(String pattern, String root, Pattern regex) {
        this.pattern = pattern;
        this.root = root;
        this.regex = regex;
    }

    /**
     * Normalizes and compiles a pattern. The first segment must be a literal folder name,
     * because it decides which top-level folder ModSync scans, and it is checked against the
     * sandbox roots like any entry path.
     */
    public static PathGlob compile(String raw, String where) throws ManifestException {
        String p = raw.strip().replace('\\', '/');
        if (p.isEmpty()) {
            throw new ManifestException(where + " is empty");
        }
        if (p.length() > ManifestCodec.MAX_PATH_LENGTH) {
            throw new ManifestException(where + " is over " + ManifestCodec.MAX_PATH_LENGTH
                + " characters");
        }
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) {
            throw new ManifestException(
                where + " must be relative to the game directory, got '" + raw + "'");
        }
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        String[] segments = p.split("/");
        for (String segment : segments) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new ManifestException(
                    where + " must not contain '.' or '..' segments, got '" + raw + "'");
            }
        }
        String root = segments[0];
        if (segments.length < 2 || root.indexOf('*') >= 0 || root.indexOf('?') >= 0) {
            throw new ManifestException(where + " must start with a literal folder such as "
                + "'mods/' or 'config/', got '" + raw + "'");
        }
        return new PathGlob(p, root, Pattern.compile(toRegex(p)));
    }

    private static String toRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c != '*' && c != '?') {
                literal.append(c);
                continue;
            }
            if (!literal.isEmpty()) {
                sb.append(Pattern.quote(literal.toString()));
                literal.setLength(0);
            }
            if (c == '?') {
                sb.append("[^/]");
            } else if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                sb.append(".*");
                i++;
            } else {
                sb.append("[^/]*");
            }
        }
        if (!literal.isEmpty()) {
            sb.append(Pattern.quote(literal.toString()));
        }
        return sb.toString();
    }

    public boolean matches(String relativePath) {
        return regex.matcher(relativePath).matches();
    }

    /** The top-level folder this pattern lives in, e.g. {@code config}. */
    public String root() {
        return root;
    }

    public String pattern() {
        return pattern;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PathGlob other && other.pattern.equals(pattern);
    }

    @Override
    public int hashCode() {
        return pattern.hashCode();
    }

    @Override
    public String toString() {
        return pattern;
    }
}

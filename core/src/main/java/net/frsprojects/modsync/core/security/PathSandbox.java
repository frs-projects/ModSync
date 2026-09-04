package net.frsprojects.modsync.core.security;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a manifest-supplied path is somewhere ModSync is willing to write.
 *
 * <p>A manifest comes from a remote server, and acting on it means writing executable jars
 * into the game directory. Every path therefore has to survive this class before anything
 * touches the filesystem. Two rules matter most:
 *
 * <ul>
 *   <li>The result must stay inside one of a small set of allowlisted directories. Escaping
 *       the game directory would let a server write to, say, the user's autostart folder.
 *   <li>Rejection is syntactic <em>and</em> semantic: shapes that only Windows treats
 *       specially (reserved device names, trailing dots, alternate data streams) are refused
 *       on every platform, so a Linux-only test run cannot pass something a Windows client
 *       would then mishandle.
 * </ul>
 */
public final class PathSandbox {

    /** Directories a server may place files into. Anything else is refused. */
    public static final Set<String> DEFAULT_ROOTS = Set.of(
        "mods",
        "config",
        "defaultconfigs",
        "resourcepacks",
        "shaderpacks",
        "kubejs",
        "scripts");

    /**
     * Windows treats these as device names regardless of extension, so a file called
     * {@code CON.jar} is not a file at all. Refused everywhere for cross-platform parity.
     */
    private static final Pattern WINDOWS_RESERVED = Pattern.compile(
        "^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(\\..*)?$", Pattern.CASE_INSENSITIVE);

    private final Path gameDir;
    private final Set<String> allowedRoots;

    public PathSandbox(Path gameDir) {
        this(gameDir, DEFAULT_ROOTS);
    }

    public PathSandbox(Path gameDir, Set<String> allowedRoots) {
        this.gameDir = gameDir.toAbsolutePath().normalize();
        this.allowedRoots = Set.copyOf(allowedRoots);
    }

    public Path gameDir() {
        return gameDir;
    }

    public Set<String> allowedRoots() {
        return allowedRoots;
    }

    /**
     * Resolves a manifest path to an absolute path inside the game directory.
     *
     * @param manifestPath a game-directory-relative, forward-slash path
     * @return the absolute destination
     * @throws SandboxException if the path is malformed, escapes the game directory, or
     *     lands outside the allowlisted roots
     */
    public Path resolve(String manifestPath) throws SandboxException {
        if (manifestPath == null || manifestPath.isBlank()) {
            throw new SandboxException("Path is empty");
        }
        // Deliberately NOT trimmed: a trailing space is exactly the Windows aliasing
        // that checkSegment() below exists to reject, and trimming here would erase it.
        String p = manifestPath;

        // Backslash is a separator on Windows, so treating it as an ordinary character
        // would let "mods\..\..\evil" through the segment checks below.
        if (p.indexOf('\\') >= 0) {
            throw new SandboxException("Path must use '/' separators, got '" + manifestPath + "'");
        }
        if (p.startsWith("/") || p.startsWith("~")) {
            throw new SandboxException("Path must be relative, got '" + manifestPath + "'");
        }
        if (p.length() > 1 && p.charAt(1) == ':') {
            throw new SandboxException("Path must not name a drive, got '" + manifestPath + "'");
        }

        String[] segments = p.split("/", -1);
        if (segments.length < 2) {
            throw new SandboxException(
                "Path must sit inside one of " + allowedRoots + ", got '" + manifestPath + "'");
        }
        for (String segment : segments) {
            checkSegment(segment, manifestPath);
        }

        String root = segments[0];
        if (!allowedRoots.contains(root.toLowerCase(Locale.ROOT))) {
            throw new SandboxException(
                "Path root '" + root + "' is not writable by ModSync. Allowed roots: "
                    + allowedRoots);
        }

        Path resolved;
        try {
            resolved = gameDir.resolve(p).normalize();
        } catch (InvalidPathException e) {
            throw new SandboxException(
                "Path is not valid on this platform: '" + manifestPath + "'");
        }

        // Belt and braces: even with every segment checked, confirm the normalized result
        // is still under the game directory before anyone acts on it.
        if (!resolved.startsWith(gameDir) || resolved.equals(gameDir)) {
            throw new SandboxException(
                "Path escapes the game directory: '" + manifestPath + "'");
        }
        checkNoSymlinkEscape(resolved, manifestPath);
        return resolved;
    }

    private void checkSegment(String segment, String original) throws SandboxException {
        if (segment.isEmpty()) {
            throw new SandboxException("Path has an empty segment: '" + original + "'");
        }
        if (segment.equals(".") || segment.equals("..")) {
            throw new SandboxException(
                "Path contains a '" + segment + "' segment: '" + original + "'");
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new SandboxException(
                    "Path contains a control character: '" + original + "'");
            }
            // ':' would open an NTFS alternate data stream; the rest are simply illegal
            // on Windows and would behave inconsistently across platforms.
            if (c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                throw new SandboxException(
                    "Path contains the illegal character '" + c + "': '" + original + "'");
            }
        }
        // Windows silently strips these, so "evil.jar." and "evil.jar" are the same file
        // there but different strings here — which would defeat hash-to-path bookkeeping.
        char last = segment.charAt(segment.length() - 1);
        if (last == '.' || Character.isWhitespace(last)) {
            throw new SandboxException(
                "Path segment must not end with '" + last + "': '" + original + "'");
        }
        if (Character.isWhitespace(segment.charAt(0))) {
            throw new SandboxException(
                "Path segment must not start with whitespace: '" + original + "'");
        }
        if (WINDOWS_RESERVED.matcher(segment).matches()) {
            throw new SandboxException(
                "Path segment '" + segment + "' is a reserved device name: '" + original + "'");
        }
    }

    /**
     * Rejects a path whose existing parent chain leaves the game directory through a
     * symlink. A pre-existing {@code mods/evil -> /etc} would otherwise pass every
     * string-level check and still write outside.
     */
    private void checkNoSymlinkEscape(Path resolved, String original) throws SandboxException {
        Path cursor = resolved.getParent();
        while (cursor != null && cursor.startsWith(gameDir)) {
            if (Files.isSymbolicLink(cursor)) {
                try {
                    Path real = cursor.toRealPath();
                    if (!real.startsWith(gameDir.toRealPath())) {
                        throw new SandboxException(
                            "Path passes through a symlink that leaves the game directory: '"
                                + original + "'");
                    }
                } catch (java.io.IOException e) {
                    throw new SandboxException(
                        "Cannot verify where the symlink at " + cursor + " points: '"
                            + original + "'");
                }
            }
            cursor = cursor.getParent();
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new SandboxException(
                "Destination is an existing symlink and will not be overwritten: '"
                    + original + "'");
        }
    }
}

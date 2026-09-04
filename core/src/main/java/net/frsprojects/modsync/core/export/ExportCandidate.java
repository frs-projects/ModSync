package net.frsprojects.modsync.core.export;

import net.frsprojects.modsync.core.manifest.Hashes;

/** One file the scanner found, before any remote lookup has run. */
public record ExportCandidate(
    /** Game-directory-relative, always '/' separated. */
    String path,
    long size,
    /** SHA-512 and SHA-1, both always present: the scanner hashes every file it keeps. */
    Hashes hashes
) {

    /** First path segment, e.g. {@code mods}. Decides policy, side and whether to look up. */
    public String root() {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    public String fileName() {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}

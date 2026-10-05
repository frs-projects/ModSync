package net.frsprojects.modsync.core.sync;

import net.frsprojects.modsync.core.hash.Hashing;
import net.frsprojects.modsync.core.manifest.Hashes;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.profile.ContentCache;
import net.frsprojects.modsync.core.profile.ModSyncPaths;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Takes files the player downloaded by hand into the content cache.
 *
 * <p>Some files cannot be fetched by ModSync (see
 * {@link net.frsprojects.modsync.core.manifest.ManualDownload}), so the player downloads them
 * in a browser and this picks them up: from the folders it is pointed at, or from files dropped
 * onto the game window. A file is accepted only if its hashes match a wanted entry, so whatever
 * else is lying in the downloads folder is never touched, and the original is left where it
 * is. Nothing outside the game directory is deleted.
 *
 * <p>Meant to be polled while the player is downloading: a file already looked at and found
 * not to match is remembered by size and modification time, so a poll that finds nothing new
 * costs a directory listing, not a rehash.
 */
public final class ManualImports {

    /** What browsers name a download that is still in progress. */
    private static final List<String> PARTIAL_SUFFIXES = List.of(
        ".part", ".partial", ".crdownload", ".download", ".tmp", ".opdownload");

    /** Only the top level of a folder is looked at; a downloads folder can be huge. */
    private static final int MAX_FILES_PER_FOLDER = 5_000;

    private final ModSyncPaths paths;
    private final ContentCache cache;
    private final List<ManifestEntry> wanted;
    /** Files already hashed and found not to match, keyed by path, valued by size+mtime. */
    private final Map<Path, String> rejected = new HashMap<>();

    public ManualImports(ModSyncPaths paths, List<ManifestEntry> wanted) {
        this.paths = paths;
        this.cache = new ContentCache(paths);
        this.wanted = List.copyOf(wanted);
    }

    /** Every wanted entry, in manifest order. */
    public List<ManifestEntry> wanted() {
        return wanted;
    }

    /** Wanted entries whose file is not in the cache yet. */
    public synchronized List<ManifestEntry> pending() {
        List<ManifestEntry> out = new ArrayList<>();
        for (ManifestEntry e : wanted) {
            if (!cache.contains(e.hashes().sha512())) {
                out.add(e);
            }
        }
        return out;
    }

    public boolean isDone(ManifestEntry entry) {
        return cache.contains(entry.hashes().sha512());
    }

    /**
     * Looks through the top level of each folder for wanted files. Folders that do not exist
     * are skipped.
     *
     * @return the entries newly taken into the cache
     */
    public synchronized List<ManifestEntry> scan(Collection<Path> folders) {
        List<Path> files = new ArrayList<>();
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (DirectoryStream<Path> dir = Files.newDirectoryStream(folder)) {
                int seen = 0;
                for (Path p : dir) {
                    if (++seen > MAX_FILES_PER_FOLDER) {
                        break;
                    }
                    files.add(p);
                }
            } catch (IOException e) {
                // An unreadable folder is the same as an empty one for this purpose.
            }
        }
        return offer(files);
    }

    /**
     * Considers specific files, e.g. ones dropped onto the game window.
     *
     * @return the entries newly taken into the cache
     */
    public synchronized List<ManifestEntry> offer(Collection<Path> files) {
        List<ManifestEntry> pending = pending();
        if (pending.isEmpty()) {
            return List.of();
        }
        List<ManifestEntry> imported = new ArrayList<>();
        for (Path file : new LinkedHashSet<>(files)) {
            List<ManifestEntry> candidates = candidatesFor(file, pending);
            if (candidates.isEmpty()) {
                continue;
            }
            List<ManifestEntry> got = tryImport(file, candidates);
            imported.addAll(got);
            pending.removeAll(got);
            if (pending.isEmpty()) {
                break;
            }
        }
        return imported;
    }

    /** Entries this file could be, judged without reading it. */
    private List<ManifestEntry> candidatesFor(Path file, List<ManifestEntry> pending) {
        String name = file.getFileName() == null ? ""
            : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith(".") || PARTIAL_SUFFIXES.stream().anyMatch(name::endsWith)) {
            return List.of();
        }
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException e) {
            return List.of();
        }
        if (!attrs.isRegularFile()) {
            return List.of();
        }
        String stamp = attrs.size() + "@" + attrs.lastModifiedTime().toMillis();
        if (stamp.equals(rejected.get(file))) {
            return List.of();
        }
        List<ManifestEntry> out = new ArrayList<>();
        for (ManifestEntry e : pending) {
            boolean sizeKnown = e.size() >= 0;
            if (sizeKnown ? e.size() == attrs.size() : nameMatches(e, name)) {
                out.add(e);
            }
        }
        if (out.isEmpty()) {
            // Not worth remembering: the size check is free.
            return out;
        }
        rejected.put(file, stamp);
        return out;
    }

    private static boolean nameMatches(ManifestEntry e, String lowerName) {
        String expected = e.manual() != null && e.manual().fileName() != null
            ? e.manual().fileName() : e.fileName();
        return expected.toLowerCase(Locale.ROOT).equals(lowerName);
    }

    /**
     * Copies first and hashes the copy, so a file that changes between the check and the copy
     * can never put unverified bytes in the cache.
     */
    private List<ManifestEntry> tryImport(Path file, List<ManifestEntry> candidates) {
        Path tmp = null;
        try {
            Files.createDirectories(paths.downloadTemp());
            tmp = Files.createTempFile(paths.downloadTemp(), "manual", ".tmp");
            Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
            Hashes actual = Hashing.hash(tmp);

            List<ManifestEntry> matched = new ArrayList<>();
            for (ManifestEntry e : candidates) {
                if (matches(e.hashes(), actual)) {
                    matched.add(e);
                }
            }
            if (matched.isEmpty()) {
                return List.of();
            }
            // Every match has this SHA-512, so one stored blob serves them all.
            cache.store(tmp, actual.sha512());
            tmp = null;
            rejected.remove(file);
            return matched;
        } catch (IOException e) {
            // Still downloading, locked, or vanished; the next poll tries again.
            rejected.remove(file);
            return List.of();
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // Scratch space; cleared on the next sync anyway.
                }
            }
        }
    }

    private static boolean matches(Hashes expected, Hashes actual) {
        if (expected.sha512() == null || !expected.sha512().equals(actual.sha512())) {
            return false;
        }
        return expected.sha1() == null || expected.sha1().equals(actual.sha1());
    }
}

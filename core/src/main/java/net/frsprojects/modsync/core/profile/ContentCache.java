package net.frsprojects.modsync.core.profile;

import net.frsprojects.modsync.core.hash.Hashing;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Content-addressed store of every file ModSync has ever fetched, keyed by SHA-512.
 *
 * <p>Two servers requiring the same mod share one copy on disk, and switching between them
 * needs no network at all. Files are placed into {@code mods/} by hard link where the
 * filesystem allows it, so the live set costs no additional space.
 *
 * <p>Symlinks are deliberately not used: on Windows they require Developer Mode or elevation,
 * and a mod loader following one behaves inconsistently across loaders.
 */
public final class ContentCache {

    private final ModSyncPaths paths;

    public ContentCache(ModSyncPaths paths) {
        this.paths = paths;
    }

    public boolean contains(String sha512) {
        return Files.isRegularFile(paths.cacheEntry(sha512));
    }

    /** Where this blob lives, or null when it is not cached. */
    public Path locate(String sha512) {
        Path p = paths.cacheEntry(sha512);
        return Files.isRegularFile(p) ? p : null;
    }

    /**
     * Moves a fully downloaded and verified file into the cache.
     *
     * @param source a file whose content hashes to {@code sha512}; consumed by this call
     * @return the file's path inside the cache
     */
    public Path store(Path source, String sha512) throws IOException {
        Path target = paths.cacheEntry(sha512);
        if (Files.isRegularFile(target)) {
            // Already cached by an earlier download of the same content.
            Files.deleteIfExists(source);
            return target;
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /** Copies an existing game file into the cache, leaving the original in place. */
    public Path adopt(Path existing, String sha512) throws IOException {
        Path target = paths.cacheEntry(sha512);
        if (Files.isRegularFile(target)) {
            return target;
        }
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(paths.downloadTemp(), "adopt", ".tmp");
        Files.copy(existing, tmp, StandardCopyOption.REPLACE_EXISTING);
        return store(tmp, sha512);
    }

    /**
     * Materialises a cached blob at {@code destination}.
     *
     * <p>Tries a hard link first — instant and free — and falls back to a copy when the
     * filesystem refuses, which happens across volumes and on filesystems without link
     * support. Callers must not assume either outcome.
     *
     * @return true if a hard link was created, false if the content was copied
     */
    public boolean materialise(String sha512, Path destination) throws IOException {
        Path source = paths.cacheEntry(sha512);
        if (!Files.isRegularFile(source)) {
            throw new IOException("Not in cache: " + sha512);
        }
        Files.createDirectories(destination.getParent());
        Files.deleteIfExists(destination);
        try {
            Files.createLink(destination, source);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return false;
        }
    }

    /**
     * Removes cached blobs that no profile references.
     *
     * @param referenced hashes still in use by any profile
     * @return bytes reclaimed
     */
    public long prune(java.util.Set<String> referenced) throws IOException {
        Path cacheRoot = paths.cacheRoot();
        if (!Files.isDirectory(cacheRoot)) {
            return 0L;
        }
        long freed = 0L;
        try (var fanout = Files.newDirectoryStream(cacheRoot)) {
            for (Path bucket : fanout) {
                if (!Files.isDirectory(bucket)) {
                    continue;
                }
                try (var entries = Files.newDirectoryStream(bucket)) {
                    for (Path entry : entries) {
                        String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!referenced.contains(name)) {
                            freed += Files.size(entry);
                            Files.delete(entry);
                        }
                    }
                }
            }
        }
        return freed;
    }

    /**
     * Re-hashes a cached blob and deletes it if the content no longer matches its name.
     * Cheap insurance against bit rot or a half-written file surviving a crash.
     */
    public boolean verify(String sha512) throws IOException {
        Path p = paths.cacheEntry(sha512);
        if (!Files.isRegularFile(p)) {
            return false;
        }
        if (Hashing.sha512(p).equals(sha512.toLowerCase(Locale.ROOT))) {
            return true;
        }
        Files.delete(p);
        return false;
    }
}

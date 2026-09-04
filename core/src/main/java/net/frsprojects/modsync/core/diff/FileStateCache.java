package net.frsprojects.modsync.core.diff;

import net.frsprojects.modsync.core.hash.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers the hash of each local file, keyed by path, size and modification time.
 *
 * <p>Without this, joining a server means SHA-512ing every jar in {@code mods/} — hundreds
 * of megabytes on a large pack, every single time. The (size, mtime) pair is the standard
 * cheap invalidation signal: it can theoretically miss an edit that preserves both, which
 * no build tool or mod distribution does in practice, and the download path verifies hashes
 * independently anyway.
 *
 * <p>Stored as tab-separated lines to match the journal, so nothing here needs Gson.
 */
public final class FileStateCache {

    private static final String HEADER = "#modsync-filestate";
    private static final int VERSION = 1;

    private final Map<String, Entry> entries = new HashMap<>();
    private boolean dirty;

    private record Entry(long size, long lastModified, String sha512) {}

    /** Hashes {@code file}, reusing a previous result when size and mtime are unchanged. */
    public String hashOf(String relativePath, Path file) throws IOException {
        long size = Files.size(file);
        long mtime = Files.getLastModifiedTime(file).toMillis();

        Entry cached = entries.get(relativePath);
        if (cached != null && cached.size() == size && cached.lastModified() == mtime) {
            return cached.sha512();
        }
        String sha512 = Hashing.sha512(file);
        entries.put(relativePath, new Entry(size, mtime, sha512));
        dirty = true;
        return sha512;
    }

    /** Drops records for files that no longer exist, so the cache cannot grow forever. */
    public void retainOnly(java.util.Set<String> livePaths) {
        if (entries.keySet().retainAll(livePaths)) {
            dirty = true;
        }
    }

    public static FileStateCache load(Path file) {
        FileStateCache cache = new FileStateCache();
        if (!Files.isRegularFile(file)) {
            return cache;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A corrupt cache is not worth failing a join over; rebuild it by rehashing.
            return cache;
        }
        if (lines.isEmpty() || !lines.get(0).startsWith(HEADER)) {
            return cache;
        }
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split("\t", -1);
            if (p.length != 4) {
                continue;
            }
            try {
                cache.entries.put(p[0],
                    new Entry(Long.parseLong(p[1]), Long.parseLong(p[2]), p[3]));
            } catch (NumberFormatException ignored) {
                // Skip the bad line; the file will simply be rehashed.
            }
        }
        return cache;
    }

    public void save(Path file) throws IOException {
        if (!dirty && Files.exists(file)) {
            return;
        }
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\t').append(VERSION).append('\n');
        entries.forEach((path, e) -> sb.append(path).append('\t').append(e.size())
            .append('\t').append(e.lastModified()).append('\t').append(e.sha512()).append('\n'));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }
}

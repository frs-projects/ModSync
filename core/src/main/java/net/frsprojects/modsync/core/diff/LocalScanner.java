package net.frsprojects.modsync.core.diff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Walks the directories ModSync manages and hashes what it finds. */
public final class LocalScanner {

    private final Path gameDir;
    private final FileStateCache stateCache;

    public LocalScanner(Path gameDir, FileStateCache stateCache) {
        this.gameDir = gameDir;
        this.stateCache = stateCache;
    }

    /**
     * Scans the given game-directory-relative roots.
     *
     * <p>Symlinks are not followed: a link out of the game directory would otherwise pull
     * unrelated files into the diff, and a link loop would hang the scan.
     */
    public List<LocalFile> scan(Set<String> roots) throws IOException {
        List<LocalFile> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String root : roots) {
            Path dir = gameDir.resolve(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !Files.isSymbolicLink(p))
                    .sorted(Comparator.naturalOrder())
                    .toList();
                for (Path file : files) {
                    String relative = toRelative(file);
                    if (!seen.add(relative)) {
                        continue;
                    }
                    found.add(new LocalFile(
                        relative,
                        Files.size(file),
                        Files.getLastModifiedTime(file).toMillis(),
                        stateCache.hashOf(relative, file)));
                }
            }
        }
        stateCache.retainOnly(seen);
        return found;
    }

    private String toRelative(Path file) {
        return gameDir.relativize(file).toString().replace('\\', '/');
    }
}

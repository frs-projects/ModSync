package net.frsprojects.modsync.core.export;

import net.frsprojects.modsync.core.hash.Hashing;
import net.frsprojects.modsync.core.manifest.Hashes;
import net.frsprojects.modsync.core.manifest.ManifestCodec;
import net.frsprojects.modsync.core.security.PathSandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Walks one game-directory root and hashes everything worth publishing.
 *
 * <p>This deliberately does not reuse {@code LocalScanner}. That scanner ends every pass with
 * {@code stateCache.retainOnly(...)}, so scanning a single folder for an export would evict
 * the hash cache for every root it did not visit and force a full rehash on the next join.
 * Export is a rare, explicit operation; paying for its own hashes is cheaper than corrupting
 * the cache the sync path depends on.
 *
 * <p>It also hashes with {@link Hashing#hash(Path)} rather than SHA-512 alone, because an
 * exported entry carries both digests: SHA-512 for integrity, SHA-1 because some Modrinth
 * records are only indexed by it.
 */
public final class ExportScanner {

    private final Path gameDir;

    public ExportScanner(Path gameDir) {
        this.gameDir = gameDir.toAbsolutePath().normalize();
    }

    /** The roots an export may be asked for, in a stable order for tab-completion. */
    public static List<String> allowedRoots() {
        List<String> roots = new ArrayList<>(PathSandbox.DEFAULT_ROOTS);
        roots.sort(Comparator.naturalOrder());
        return List.copyOf(roots);
    }

    public static boolean isAllowedRoot(String root) {
        return PathSandbox.DEFAULT_ROOTS.contains(root);
    }

    public List<ExportCandidate> scan(String root) throws ExportException, IOException {
        if (!isAllowedRoot(root)) {
            throw new ExportException("'" + root + "' is not an exportable folder. Expected one of "
                + String.join(", ", allowedRoots()) + ".");
        }
        Path dir = gameDir.resolve(root);
        if (!Files.isDirectory(dir)) {
            throw new ExportException("There is no '" + root + "' folder in " + gameDir + ".");
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                // Symlinks are skipped for the same reason the sync scanner skips them: the
                // target may sit outside the game directory entirely.
                .filter(p -> !Files.isSymbolicLink(p))
                .filter(ExportScanner::isPublishable)
                .sorted()
                .forEach(files::add);
        }

        if (files.size() > ManifestCodec.MAX_FILES) {
            throw new ExportException(root + " holds " + files.size() + " files, over the "
                + ManifestCodec.MAX_FILES + " a manifest may list. ModSync could write this "
                + "file but could not read it back.");
        }

        List<ExportCandidate> out = new ArrayList<>(files.size());
        for (Path file : files) {
            String relative = relativize(file);
            if (relative.length() > ManifestCodec.MAX_PATH_LENGTH) {
                throw new ExportException("Path is longer than the "
                    + ManifestCodec.MAX_PATH_LENGTH + " characters a manifest allows: " + relative);
            }
            long size = Files.size(file);
            if (size > ManifestCodec.MAX_FILE_SIZE) {
                throw new ExportException(relative + " is " + size + " bytes, over the manifest "
                    + "limit of " + ManifestCodec.MAX_FILE_SIZE + ".");
            }
            Hashes hashes = Hashing.hash(file);
            out.add(new ExportCandidate(relative, size, hashes));
        }
        return List.copyOf(out);
    }

    /**
     * A disabled mod is not part of the pack, and a dotfile is almost always a lockfile or an
     * editor artefact rather than content. Publishing either produces an entry that every
     * client then has to be told to ignore.
     */
    private static boolean isPublishable(Path file) {
        String name = file.getFileName().toString();
        return !name.startsWith(".") && !name.endsWith(".disabled");
    }

    private String relativize(Path file) {
        return gameDir.relativize(file).toString().replace('\\', '/');
    }
}

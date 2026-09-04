package net.frsprojects.modsync.core.diff;

/**
 * A file that exists in the game directory right now.
 *
 * @param path game-directory-relative, forward slashes
 * @param size bytes
 * @param lastModified epoch millis, used only as a hash-cache key
 * @param sha512 content hash
 */
public record LocalFile(String path, long size, long lastModified, String sha512) {

    public String fileName() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}

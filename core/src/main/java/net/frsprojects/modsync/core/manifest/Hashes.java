package net.frsprojects.modsync.core.manifest;

import java.util.Locale;
import java.util.Objects;

/**
 * Content hashes for one entry.
 *
 * <p>SHA-512 is the primary: it is what integrity checking uses, and it doubles as
 * Modrinth's lookup key, so one hash serves both purposes. SHA-1 is carried only
 * because some Modrinth data predates SHA-512 indexing. Murmur2 (CurseForge) is
 * deliberately absent — it is computed on demand during export, never trusted for
 * integrity, since it is a non-cryptographic 32-bit hash.
 */
public record Hashes(String sha512, String sha1) {

    public Hashes {
        sha512 = normalize(sha512);
        sha1 = normalize(sha1);
    }

    public static Hashes ofSha512(String sha512) {
        return new Hashes(sha512, null);
    }

    private static String normalize(String hex) {
        return hex == null ? null : hex.trim().toLowerCase(Locale.ROOT);
    }

    /** @throws ManifestException if the required SHA-512 is missing or malformed */
    public void validate(String where) throws ManifestException {
        requireHex(sha512, 128, where + ".hashes.sha512", true);
        requireHex(sha1, 40, where + ".hashes.sha1", false);
    }

    private static void requireHex(String value, int len, String where, boolean required)
            throws ManifestException {
        if (value == null || value.isEmpty()) {
            if (required) {
                throw new ManifestException(where + " is required");
            }
            return;
        }
        if (value.length() != len) {
            throw new ManifestException(
                where + " must be " + len + " hex characters, got " + value.length());
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new ManifestException(where + " contains a non-hex character at index " + i);
            }
        }
    }

    /** True when {@code other} agrees with every hash both sides actually carry. */
    public boolean matches(Hashes other) {
        Objects.requireNonNull(other, "other");
        if (sha512 != null && other.sha512 != null) {
            return sha512.equals(other.sha512);
        }
        if (sha1 != null && other.sha1 != null) {
            return sha1.equals(other.sha1);
        }
        // No algorithm in common means we cannot prove equality, so we must not claim it.
        return false;
    }
}

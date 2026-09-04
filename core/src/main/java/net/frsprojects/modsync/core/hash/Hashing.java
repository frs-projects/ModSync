package net.frsprojects.modsync.core.hash;

import net.frsprojects.modsync.core.manifest.Hashes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * File hashing.
 *
 * <p>SHA-512 is the integrity hash and doubles as Modrinth's lookup key, so a single pass
 * over a file serves both purposes. SHA-1 is computed alongside it only because some
 * Modrinth records are indexed by SHA-1 alone.
 */
public final class Hashing {

    private static final int BUFFER = 1 << 16;

    private Hashing() {}

    /** Computes SHA-512 and SHA-1 in one pass over the file. */
    public static Hashes hash(Path file) throws IOException {
        MessageDigest sha512 = digest("SHA-512");
        MessageDigest sha1 = digest("SHA-1");
        byte[] buf = new byte[BUFFER];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buf)) != -1) {
                sha512.update(buf, 0, read);
                sha1.update(buf, 0, read);
            }
        }
        return new Hashes(toHex(sha512.digest()), toHex(sha1.digest()));
    }

    /** Computes SHA-512 only, for the common verify-what-we-just-downloaded case. */
    public static String sha512(Path file) throws IOException {
        MessageDigest md = digest("SHA-512");
        byte[] buf = new byte[BUFFER];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
        }
        return toHex(md.digest());
    }

    /** Wraps a stream so it can be hashed while it is being written to disk. */
    public static DigestInputStream sha512Stream(InputStream in) {
        return new DigestInputStream(in, digest("SHA-512"));
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 and SHA-512 are mandated by the Java platform.
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }
}

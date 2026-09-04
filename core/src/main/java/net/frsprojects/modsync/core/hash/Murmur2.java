package net.frsprojects.modsync.core.hash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CurseForge's file fingerprint: MurmurHash2 (32-bit, seed 1) over the file with
 * whitespace bytes stripped.
 *
 * <p>Used only to ask CurseForge "which project is this file?" during export. It is a
 * non-cryptographic 32-bit hash and is never used for integrity — that is SHA-512's job.
 */
public final class Murmur2 {

    private static final int SEED = 1;
    private static final int M = 0x5bd1e995;
    private static final int R = 24;

    private Murmur2() {}

    /** Fingerprints a file the way CurseForge's {@code /v1/fingerprints} endpoint expects. */
    public static long fingerprint(Path file) throws IOException {
        byte[] data;
        try (InputStream in = Files.newInputStream(file)) {
            data = in.readAllBytes();
        }
        return fingerprint(data);
    }

    static long fingerprint(byte[] raw) {
        // CurseForge strips \t \n \r and space before hashing.
        byte[] stripped = new byte[raw.length];
        int len = 0;
        for (byte b : raw) {
            if (b != 9 && b != 10 && b != 13 && b != 32) {
                stripped[len++] = b;
            }
        }
        return Integer.toUnsignedLong(hash(stripped, len));
    }

    private static int hash(byte[] data, int length) {
        int h = SEED ^ length;
        int i = 0;

        while (length - i >= 4) {
            int k = (data[i] & 0xFF)
                | ((data[i + 1] & 0xFF) << 8)
                | ((data[i + 2] & 0xFF) << 16)
                | ((data[i + 3] & 0xFF) << 24);
            k *= M;
            k ^= k >>> R;
            k *= M;
            h *= M;
            h ^= k;
            i += 4;
        }

        switch (length - i) {
            case 3:
                h ^= (data[i + 2] & 0xFF) << 16;
                // fall through
            case 2:
                h ^= (data[i + 1] & 0xFF) << 8;
                // fall through
            case 1:
                h ^= (data[i] & 0xFF);
                h *= M;
                break;
            default:
                break;
        }

        h ^= h >>> 13;
        h *= M;
        h ^= h >>> 15;
        return h;
    }
}

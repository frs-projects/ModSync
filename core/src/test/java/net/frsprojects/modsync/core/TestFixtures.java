package net.frsprojects.modsync.core;

import net.frsprojects.modsync.core.manifest.Hashes;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Policy;
import net.frsprojects.modsync.core.manifest.Side;
import net.frsprojects.modsync.core.manifest.SyncManifest;
import net.frsprojects.modsync.core.manifest.UnlistedPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;

/** Shared builders so tests read as intent rather than as record constructors. */
public final class TestFixtures {

    private TestFixtures() {}

    public static String sha512Of(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes a file with the given content and returns its SHA-512. */
    public static String writeFile(Path gameDir, String relative, String content)
            throws IOException {
        Path file = gameDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return sha512Of(content);
    }

    public static ManifestEntry entry(String path, String content, Policy policy) {
        return entry(path, content, policy, List.of("https://cdn.modrinth.com/x.jar"));
    }

    public static ManifestEntry entry(String path, String content, Policy policy,
            List<String> urls) {
        return new ManifestEntry(
            null,
            path.substring(path.lastIndexOf('/') + 1),
            null,
            path,
            content.getBytes(StandardCharsets.UTF_8).length,
            Hashes.ofSha512(sha512Of(content)),
            urls,
            policy,
            Side.BOTH,
            List.of(),
            List.of(),
            null,
            policy.defaultSelected());
    }

    public static SyncManifest manifest(List<ManifestEntry> entries) {
        return manifest(entries, UnlistedPolicy.QUARANTINE);
    }

    public static SyncManifest manifest(List<ManifestEntry> entries, UnlistedPolicy unlisted) {
        return new SyncManifest(1, "test-pack", "Test Pack", "1",
            Instant.parse("2026-09-03T00:00:00Z"), unlisted, entries);
    }
}

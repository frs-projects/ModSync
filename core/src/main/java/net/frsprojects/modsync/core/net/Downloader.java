package net.frsprojects.modsync.core.net;

import net.frsprojects.modsync.core.hash.Hashing;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.profile.ContentCache;
import net.frsprojects.modsync.core.profile.ModSyncPaths;
import net.frsprojects.modsync.core.security.HostAllowlist;
import net.frsprojects.modsync.core.security.SandboxException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Fetches manifest entries into the content cache.
 *
 * <p>Content is streamed straight to a temp file while being hashed, then verified before
 * it is moved into the cache — so a file that fails its hash never reaches a location
 * anything else will read, and a truncated download cannot be mistaken for a complete one.
 *
 * <p>Every URL passes the {@link HostAllowlist} first. Mirrors are tried in order, and the
 * whole entry fails only when all of them do.
 */
public final class Downloader implements AutoCloseable {

    private static final int BUFFER = 1 << 16;
    private static final int MAX_ATTEMPTS_PER_URL = 2;

    private final HttpClient http;
    private final ContentCache cache;
    private final ModSyncPaths paths;
    private final HostAllowlist allowlist;
    private final ExecutorService pool;
    private final String userAgent;

    public Downloader(ModSyncPaths paths, ContentCache cache, HostAllowlist allowlist,
            int parallelism, String userAgent) {
        this.paths = paths;
        this.cache = cache;
        this.allowlist = allowlist;
        this.userAgent = userAgent;
        this.http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
        this.pool = Executors.newFixedThreadPool(Math.max(1, parallelism), r -> {
            Thread t = new Thread(r, "modsync-download");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Downloads every entry that is not already cached.
     *
     * @return the entries that could not be fetched, paired with why
     */
    public List<Failure> fetchAll(List<ManifestEntry> entries, DownloadProgress progress)
            throws IOException {
        Files.createDirectories(paths.downloadTemp());
        Files.createDirectories(paths.cacheRoot());

        List<Future<Failure>> futures = new ArrayList<>(entries.size());
        for (ManifestEntry entry : entries) {
            Callable<Failure> task = () -> {
                try {
                    fetch(entry, progress);
                    return null;
                } catch (IOException e) {
                    progress.failed(entry.label(), e.getMessage());
                    return new Failure(entry, e.getMessage());
                }
            };
            futures.add(pool.submit(task));
        }

        List<Failure> failures = new ArrayList<>();
        for (Future<Failure> f : futures) {
            try {
                Failure failure = f.get();
                if (failure != null) {
                    failures.add(failure);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DownloadException("Download interrupted", e);
            } catch (ExecutionException e) {
                throw new DownloadException("Download failed unexpectedly", e.getCause());
            }
        }
        return failures;
    }

    /** Downloads one entry into the cache, trying each mirror in turn. */
    public void fetch(ManifestEntry entry, DownloadProgress progress) throws IOException {
        String wanted = entry.hashes().sha512();
        if (wanted == null) {
            throw new DownloadException(entry.label() + " has no SHA-512 to verify against");
        }
        if (cache.contains(wanted)) {
            return;
        }
        if (entry.urls().isEmpty()) {
            throw new DownloadException(entry.label() + " has no download URL");
        }

        progress.started(entry.label(), entry.size());
        List<String> problems = new ArrayList<>();

        for (String url : entry.urls()) {
            URI uri;
            try {
                uri = allowlist.check(url);
            } catch (SandboxException e) {
                problems.add(e.getMessage());
                continue;
            }
            for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_URL; attempt++) {
                try {
                    Path temp = downloadTo(uri, entry, wanted, progress);
                    cache.store(temp, wanted);
                    progress.finished(entry.label());
                    return;
                } catch (IOException e) {
                    problems.add(url + ": " + e.getMessage());
                    progress.retrying(entry.label(), url, e.getMessage(), attempt);
                    if (attempt < MAX_ATTEMPTS_PER_URL) {
                        try {
                            sleepBackoff(attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new DownloadException("Download interrupted", ie);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DownloadException("Download interrupted", e);
                }
            }
        }
        throw new DownloadException(
            "Could not download " + entry.label() + " from any mirror: "
                + String.join("; ", problems));
    }

    private Path downloadTo(URI uri, ManifestEntry entry, String wantedSha512,
            DownloadProgress progress) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .timeout(Duration.ofMinutes(10))
            .GET()
            .build();

        HttpResponse<InputStream> response =
            http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            drain(response.body());
            throw new DownloadException("HTTP " + response.statusCode());
        }

        Path temp = Files.createTempFile(paths.downloadTemp(), "dl", ".part");
        long written = 0L;
        try (DigestInputStream in = Hashing.sha512Stream(response.body());
             OutputStream out = Files.newOutputStream(temp)) {
            byte[] buf = new byte[BUFFER];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                written += read;
                if (entry.size() > 0 && written > entry.size()) {
                    throw new DownloadException(
                        "Server sent more than the declared " + entry.size() + " bytes");
                }
                progress.advanced(entry.label(), written, entry.size());
            }
            out.flush();

            String actual = Hashing.toHex(in.getMessageDigest().digest());
            if (!actual.equals(wantedSha512.toLowerCase(Locale.ROOT))) {
                throw new DownloadException(
                    "SHA-512 mismatch: expected " + shorten(wantedSha512)
                        + ", got " + shorten(actual));
            }
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        if (entry.size() >= 0 && written != entry.size()) {
            Files.deleteIfExists(temp);
            throw new DownloadException(
                "Expected " + entry.size() + " bytes but received " + written);
        }
        return temp;
    }

    private static void drain(InputStream body) {
        try (InputStream in = body) {
            in.readAllBytes();
        } catch (IOException ignored) {
            // Best effort: we are already failing this attempt.
        }
    }

    private static void sleepBackoff(int attempt) throws InterruptedException {
        Thread.sleep(Math.min(4_000L, 500L * (1L << (attempt - 1))));
    }

    private static String shorten(String hex) {
        return hex.length() <= 16 ? hex : hex.substring(0, 16) + "...";
    }

    @Override
    public void close() {
        pool.shutdownNow();
        // HttpClient.close() is Java 21+, and :core targets 17 because 1.20.1 is in the
        // matrix. The client's own threads are daemons, so letting it go is safe here.
    }

    /** One entry that could not be fetched. */
    public record Failure(ManifestEntry entry, String reason) {}
}

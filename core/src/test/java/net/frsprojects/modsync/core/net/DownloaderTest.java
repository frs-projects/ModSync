package net.frsprojects.modsync.core.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.frsprojects.modsync.core.TestFixtures;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Policy;
import net.frsprojects.modsync.core.profile.ContentCache;
import net.frsprojects.modsync.core.profile.ModSyncPaths;
import net.frsprojects.modsync.core.security.HostAllowlist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the downloader against a real HTTP server rather than a mock. */
class DownloaderTest {

    @TempDir
    Path gameDir;

    private ModSyncPaths paths;
    private ContentCache cache;
    private HttpServer server;
    private String base;
    private Downloader downloader;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        paths = new ModSyncPaths(gameDir);
        paths.createDirectories();
        cache = new ContentCache(paths);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        // 127.0.0.1 is trusted the way a joined server would be, which also exercises the
        // plain-http exemption for the server the player connected to.
        downloader = new Downloader(paths, cache,
            HostAllowlist.defaults().plusServer("127.0.0.1"), 4, "ModSync-Test");
    }

    @AfterEach
    void tearDown() {
        downloader.close();
        server.stop(0);
    }

    private void serve(String path, String body) {
        serve(path, 200, body);
    }

    private void serve(String path, int status, String body) {
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            respond(exchange, status, body);
        });
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private ManifestEntry entry(String content, List<String> urls) {
        return TestFixtures.entry("mods/a.jar", content, Policy.REQUIRE, urls);
    }

    @Test
    void downloadsAndCachesAFile() throws IOException {
        serve("/a.jar", "mod-content");
        ManifestEntry e = entry("mod-content", List.of(base + "/a.jar"));

        downloader.fetch(e, DownloadProgress.NONE);

        assertTrue(cache.contains(e.hashes().sha512()));
        assertEquals("mod-content", Files.readString(cache.locate(e.hashes().sha512())));
    }

    /** A file whose content does not match its hash must never reach the cache. */
    @Test
    void rejectsContentThatFailsItsHash() {
        serve("/a.jar", "tampered-content");
        ManifestEntry e = entry("expected-content", List.of(base + "/a.jar"));

        DownloadException ex = assertThrows(DownloadException.class,
            () -> downloader.fetch(e, DownloadProgress.NONE));
        assertTrue(ex.getMessage().contains("SHA-512 mismatch"), ex.getMessage());
        assertFalse(cache.contains(e.hashes().sha512()));
    }

    @Test
    void rejectsAResponseShorterThanTheDeclaredSize() {
        serve("/a.jar", "short");
        // The entry declares the length of "the full content", but the server sends less.
        ManifestEntry declared = entry("the full content", List.of(base + "/a.jar"));

        assertThrows(DownloadException.class,
            () -> downloader.fetch(declared, DownloadProgress.NONE));
        assertFalse(cache.contains(declared.hashes().sha512()));
    }

    @Test
    void fallsBackToTheNextMirror() throws IOException {
        serve("/broken.jar", 500, "nope");
        serve("/good.jar", "mod-content");
        ManifestEntry e = entry("mod-content",
            List.of(base + "/broken.jar", base + "/good.jar"));

        downloader.fetch(e, DownloadProgress.NONE);

        assertTrue(cache.contains(e.hashes().sha512()));
    }

    /** A mirror on a host outside the allowlist is skipped, not fatal. */
    @Test
    void skipsDisallowedMirrorsAndUsesAnAllowedOne() throws IOException {
        serve("/good.jar", "mod-content");
        ManifestEntry e = entry("mod-content",
            List.of("https://evil.example.com/a.jar", base + "/good.jar"));

        downloader.fetch(e, DownloadProgress.NONE);

        assertTrue(cache.contains(e.hashes().sha512()));
    }

    @Test
    void failsWhenEveryMirrorFails() {
        serve("/one.jar", 404, "");
        serve("/two.jar", 500, "");
        ManifestEntry e = entry("mod-content", List.of(base + "/one.jar", base + "/two.jar"));

        DownloadException ex = assertThrows(DownloadException.class,
            () -> downloader.fetch(e, DownloadProgress.NONE));
        assertTrue(ex.getMessage().contains("any mirror"));
    }

    @Test
    void alreadyCachedContentIsNotFetchedAgain() throws IOException {
        serve("/a.jar", "mod-content");
        ManifestEntry e = entry("mod-content", List.of(base + "/a.jar"));

        downloader.fetch(e, DownloadProgress.NONE);
        int afterFirst = requests.get();
        downloader.fetch(e, DownloadProgress.NONE);

        assertEquals(afterFirst, requests.get(), "a cached file must not be re-requested");
    }

    @Test
    void anEntryWithNoUrlFailsClearly() {
        ManifestEntry e = entry("mod-content", List.of());
        DownloadException ex = assertThrows(DownloadException.class,
            () -> downloader.fetch(e, DownloadProgress.NONE));
        assertTrue(ex.getMessage().contains("no download URL"));
    }

    @Test
    void fetchAllReportsPerEntryFailuresWithoutAbortingTheRest() throws IOException {
        serve("/ok.jar", "ok-content");
        serve("/bad.jar", 404, "");

        List<Downloader.Failure> failures = downloader.fetchAll(List.of(
            TestFixtures.entry("mods/ok.jar", "ok-content", Policy.REQUIRE,
                List.of(base + "/ok.jar")),
            TestFixtures.entry("mods/bad.jar", "bad-content", Policy.REQUIRE,
                List.of(base + "/bad.jar"))), DownloadProgress.NONE);

        assertEquals(1, failures.size());
        assertEquals("bad.jar", failures.get(0).entry().label());
        assertTrue(cache.contains(TestFixtures.sha512Of("ok-content")),
            "one failure must not prevent the others from completing");
    }

    @Test
    void progressIsReportedForASuccessfulDownload() throws IOException {
        serve("/a.jar", "mod-content");
        ManifestEntry e = entry("mod-content", List.of(base + "/a.jar"));

        var started = new AtomicInteger();
        var finished = new AtomicInteger();
        downloader.fetch(e, new DownloadProgress() {
            @Override public void started(String label, long expected) { started.incrementAndGet(); }
            @Override public void finished(String label) { finished.incrementAndGet(); }
        });

        assertEquals(1, started.get());
        assertEquals(1, finished.get());
    }

    @Test
    void noPartialFilesAreLeftBehindAfterAFailure() {
        serve("/a.jar", "tampered");
        ManifestEntry e = entry("expected", List.of(base + "/a.jar"));

        assertThrows(DownloadException.class, () -> downloader.fetch(e, DownloadProgress.NONE));

        try (var stream = Files.list(paths.downloadTemp())) {
            assertEquals(0L, stream.count(), "temp directory must be clean after a failure");
        } catch (IOException io) {
            throw new AssertionError(io);
        }
    }
}

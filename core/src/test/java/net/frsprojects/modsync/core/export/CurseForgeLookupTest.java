package net.frsprojects.modsync.core.export;

import com.sun.net.httpserver.HttpServer;

import net.frsprojects.modsync.core.TestFixtures;
import net.frsprojects.modsync.core.hash.Murmur2;
import net.frsprojects.modsync.core.manifest.Hashes;
import net.frsprojects.modsync.core.security.HostAllowlist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the CurseForge lookup against a real HTTP server rather than a mock. */
class CurseForgeLookupTest {

    @TempDir
    Path gameDir;

    private HttpServer server;
    private JsonHttp http;
    private String base;
    private final AtomicReference<String> modsBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        http = new JsonHttp(HostAllowlist.defaults().plusServer("127.0.0.1"), "ModSync-Test");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        http.close();
    }

    private void respond(String path, int status, String body, AtomicReference<String> seen) {
        server.createContext(path, exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
            if (seen != null) {
                seen.set(request);
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
    }

    private ExportCandidate candidate(String path, String content) throws IOException {
        TestFixtures.writeFile(gameDir, path, content);
        return new ExportCandidate(path, content.length(),
            new Hashes(TestFixtures.sha512Of(content), null));
    }

    private long print(String path) throws IOException {
        return Murmur2.fingerprint(gameDir.resolve(path));
    }

    private static String match(long print, long modId, long fileId, String fileName,
            String downloadUrl) {
        return "{\"id\":" + modId + ",\"file\":{\"id\":" + fileId + ",\"modId\":" + modId
            + ",\"fileName\":\"" + fileName + "\",\"fileFingerprint\":" + print
            + ",\"downloadUrl\":" + (downloadUrl == null ? "null" : "\"" + downloadUrl + "\"")
            + "}}";
    }

    @Test
    void aWithheldFileGetsItsProjectsDownloadPage() throws Exception {
        ExportCandidate open = candidate("mods/open.jar", "open-bytes");
        ExportCandidate jei = candidate("mods/jei.jar", "jei-bytes");
        respond("/fingerprints", 200, "{\"data\":{\"exactMatches\":["
            + match(print("mods/open.jar"), 11, 101, "open.jar",
                "https://edge.forgecdn.net/files/101/open.jar") + ","
            + match(print("mods/jei.jar"), 238222, 5846880, "jei-1.21.1.jar", null)
            + "]}}", null);
        respond("/mods", 200, "{\"data\":[{\"id\":238222,\"links\":{"
            + "\"websiteUrl\":\"https://www.curseforge.com/minecraft/mc-mods/jei/\"}}]}",
            modsBody);

        Map<String, ModMetadataLookup.Resolved> out =
            new CurseForgeLookup(http, "key", gameDir, base).resolve(List.of(open, jei));

        assertEquals("https://edge.forgecdn.net/files/101/open.jar",
            out.get("mods/open.jar").url());
        assertNull(out.get("mods/open.jar").manual());

        ModMetadataLookup.Resolved r = out.get("mods/jei.jar");
        assertEquals("curseforge:238222", r.id());
        assertNull(r.url());
        assertEquals("https://www.curseforge.com/minecraft/mc-mods/jei/download/5846880",
            r.manual().url());
        assertEquals("jei-1.21.1.jar", r.manual().fileName());
        // Only the withheld project is asked about.
        assertTrue(modsBody.get().contains("238222"), modsBody.get());
        assertTrue(!modsBody.get().contains("\"11\"") && !modsBody.get().contains("[11"),
            modsBody.get());
    }

    @Test
    void aFailedProjectLookupStillReturnsTheFingerprintMatch() throws Exception {
        ExportCandidate jei = candidate("mods/jei.jar", "jei-bytes");
        respond("/fingerprints", 200, "{\"data\":{\"exactMatches\":["
            + match(print("mods/jei.jar"), 238222, 5846880, "jei.jar", null) + "]}}", null);
        respond("/mods", 500, "oops", null);

        ModMetadataLookup.Resolved r = new CurseForgeLookup(http, "key", gameDir, base)
            .resolve(List.of(jei)).get("mods/jei.jar");

        assertEquals("curseforge:238222", r.id());
        assertNull(r.url());
        assertNull(r.manual());
    }
}

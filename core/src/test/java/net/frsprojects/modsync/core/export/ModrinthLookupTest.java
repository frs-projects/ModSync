package net.frsprojects.modsync.core.export;

import com.sun.net.httpserver.HttpServer;

import net.frsprojects.modsync.core.TestFixtures;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the Modrinth lookup against a real HTTP server rather than a mock. */
class ModrinthLookupTest {

    @TempDir
    Path gameDir;

    private HttpServer server;
    private JsonHttp http;
    private String base;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

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

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
    }

    private static ExportCandidate candidate(String path, String content) {
        return new ExportCandidate(path, content.length(),
            new Hashes(TestFixtures.sha512Of(content), null));
    }

    @Test
    void resolvesTheFileWhoseHashWasAskedAbout() throws Exception {
        String sodium = TestFixtures.sha512Of("sodium");
        // A version carries a sources jar alongside the mod, so picking the first file would
        // hand the player the wrong artefact.
        respond("/version_files", 200, "{"
            + "\"" + sodium + "\": {"
            + "  \"project_id\": \"AANobbMI\","
            + "  \"files\": ["
            + "    {\"url\": \"https://cdn.modrinth.com/sources.jar\", \"primary\": false,"
            + "     \"hashes\": {\"sha512\": \"deadbeef\"}},"
            + "    {\"url\": \"https://cdn.modrinth.com/sodium.jar\", \"primary\": true,"
            + "     \"hashes\": {\"sha512\": \"" + sodium + "\"}}"
            + "  ]}}");

        Map<String, ModMetadataLookup.Resolved> out =
            new ModrinthLookup(http, base).resolve(List.of(candidate("mods/sodium.jar", "sodium")));

        assertEquals("https://cdn.modrinth.com/sodium.jar", out.get("mods/sodium.jar").url());
        assertEquals("modrinth:AANobbMI", out.get("mods/sodium.jar").id());
        assertTrue(lastBody.get().contains("\"algorithm\":\"sha512\""));
        assertTrue(lastBody.get().contains(sodium));
    }

    @Test
    void omitsFilesModrinthDoesNotKnow() throws Exception {
        respond("/version_files", 200, "{}");

        Map<String, ModMetadataLookup.Resolved> out =
            new ModrinthLookup(http, base).resolve(List.of(candidate("mods/private.jar", "x")));

        assertTrue(out.isEmpty());
    }

    @Test
    void surfacesAnUnusableApiKeyReadably() {
        respond("/version_files", 403, "{\"error\":\"unauthorized\"}");

        IOException e = assertThrows(IOException.class, () ->
            new ModrinthLookup(http, base).resolve(List.of(candidate("mods/a.jar", "a"))));
        assertTrue(e.getMessage().contains("403"));
        assertTrue(e.getMessage().contains("curseForgeApiKey"));
    }

    @Test
    void refusesAHostOutsideTheAllowlist() {
        assertThrows(IOException.class, () ->
            new ModrinthLookup(http, "http://evil.example/v2")
                .resolve(List.of(candidate("mods/a.jar", "a"))));
    }

    @Test
    void mapsOneAnswerOntoEveryDuplicateOfThatFile() throws Exception {
        String hash = TestFixtures.sha512Of("same");
        respond("/version_files", 200, "{"
            + "\"" + hash + "\": {\"project_id\": \"P\", \"files\": ["
            + "  {\"url\": \"https://cdn.modrinth.com/same.jar\", \"primary\": true,"
            + "   \"hashes\": {\"sha512\": \"" + hash + "\"}}]}}");

        Map<String, ModMetadataLookup.Resolved> out = new ModrinthLookup(http, base).resolve(
            List.of(candidate("mods/a.jar", "same"), candidate("mods/copy.jar", "same")));

        assertEquals(2, out.size());
        assertEquals("https://cdn.modrinth.com/same.jar", out.get("mods/copy.jar").url());
        assertEquals(1, requests.get(), "duplicates must not cost a second request");
    }

    @Test
    void aVersionWithNoUsableFileResolvesToNoUrl() throws Exception {
        String hash = TestFixtures.sha512Of("odd");
        respond("/version_files", 200,
            "{\"" + hash + "\": {\"project_id\": \"P\", \"files\": []}}");

        Map<String, ModMetadataLookup.Resolved> out =
            new ModrinthLookup(http, base).resolve(List.of(candidate("mods/odd.jar", "odd")));

        assertEquals("modrinth:P", out.get("mods/odd.jar").id());
        assertNull(out.get("mods/odd.jar").url());
    }
}

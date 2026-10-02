package net.frsprojects.modsync.core.config;

import net.frsprojects.modsync.core.profile.ModSyncPaths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerManifestsTest {

    @TempDir
    Path dir;

    private Path write(String json) throws IOException {
        Path file = dir.resolve("modsync-servers.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    /** Shipped with the pack in config/, apart from the personal client config. */
    @Test
    void livesInTheConfigFolderApartFromTheClientConfig() {
        ModSyncPaths paths = new ModSyncPaths(dir);
        assertEquals(dir.resolve("config").resolve("modsync-servers.json"),
            paths.serverManifests());
        assertFalse(paths.config().startsWith(dir.resolve("config")),
            "the client config holds an API key and must stay out of config/");
    }

    @Test
    void aMissingFileIsCreatedEmpty() throws IOException {
        ModSyncPaths paths = new ModSyncPaths(dir);

        assertEquals(ServerManifests.empty(), ServerManifests.loadOrCreate(paths));
        assertTrue(Files.readString(paths.serverManifests()).contains("\"servers\": {}"));
    }

    @Test
    void urlsMatchHostCaseInsensitivelyAndDefaultThePort() throws IOException {
        ServerManifests servers = ServerManifests.load(write("{\"servers\":{"
            + "\"Play.Example.net\":\"https://e/a.json\","
            + "\"mc.example.net:25566\":\" https://e/b.json \","
            + "\"blank.example\":\"\"}}"));

        assertEquals("https://e/a.json", servers.urlFor("play.example.net", 25565));
        assertNull(servers.urlFor("play.example.net", 25566),
            "a key without a port means the default port only");
        assertEquals("https://e/b.json", servers.urlFor("MC.example.net", 25566));
        assertNull(servers.urlFor("mc.example.net", 25565));
        assertNull(servers.urlFor("blank.example", 25565));
    }

    /** An easy mistake that means the same thing: the object wrapped in an array. */
    @Test
    void anArrayOfObjectsIsAcceptedToo() throws IOException {
        ServerManifests servers = ServerManifests.load(write("{\"servers\": [\n"
            + "  { \"eub1.taczbg.lan\": \"https://control.taczbg.net/p/modsync/taczbg.json\" }\n"
            + "]}"));

        assertEquals("https://control.taczbg.net/p/modsync/taczbg.json",
            servers.urlFor("eub1.taczbg.lan", 25565));
    }

    /** Silently ignoring a malformed entry looks exactly like a server with no manifest. */
    @Test
    void malformedServersFailLoudly() throws IOException {
        for (String bad : new String[] {
                "\"https://e/m.json\"", "[\"https://e/m.json\"]", "{\"host\": {\"url\": 1}}"}) {
            Path file = write("{\"servers\": " + bad + "}");
            IOException e = assertThrows(IOException.class, () -> ServerManifests.load(file), bad);
            assertTrue(e.getMessage().contains("servers"), e.getMessage());
        }
        Path corrupt = write("{ not json");
        assertThrows(IOException.class, () -> ServerManifests.load(corrupt));
    }

    @Test
    void roundTripsThroughDiskInOrder() throws IOException {
        Path file = dir.resolve("modsync-servers.json");
        Map<String, String> ordered = new java.util.LinkedHashMap<>();
        ordered.put("z.example", "https://e/z.json");
        ordered.put("a.example", "https://e/a.json");
        ServerManifests servers = new ServerManifests(1, ordered);

        servers.save(file);

        ServerManifests loaded = ServerManifests.load(file);
        assertEquals(servers, loaded);
        assertEquals(List.of("z.example", "a.example"), List.copyOf(loaded.servers().keySet()));
    }

    /**
     * 0.1.2 kept the URLs in the client config. They move to the new file, and leave the client
     * config so there is one place to edit them; everything else in it survives.
     */
    @Test
    void overridesInAnOldClientConfigAreMovedHere() throws IOException {
        ModSyncPaths paths = new ModSyncPaths(dir);
        Files.createDirectories(paths.config().getParent());
        // The file a player actually had, array mistake included.
        Files.writeString(paths.config(), "{\n"
            + "  \"alwaysKeep\": [\"mods/mine.jar\"],\n"
            + "  \"manifestOverrides\": [\n"
            + "    { \"eub1.taczbg.lan\": \"https://control.taczbg.net/p/modsync/taczbg.json\" }\n"
            + "  ],\n"
            + "  \"curseForgeApiKey\": \"secret\"\n"
            + "}", StandardCharsets.UTF_8);

        ServerManifests servers = ServerManifests.loadOrCreate(paths);

        assertEquals("https://control.taczbg.net/p/modsync/taczbg.json",
            servers.urlFor("eub1.taczbg.lan", 25565));
        assertEquals(servers, ServerManifests.load(paths.serverManifests()));
        String client = Files.readString(paths.config());
        assertFalse(client.contains("manifestOverrides"), client);
        ModSyncConfig config = ModSyncConfig.load(paths.config());
        assertEquals(List.of("mods/mine.jar"), config.alwaysKeep());
        assertEquals("secret", config.curseForgeApiKey());
        assertFalse(Files.readString(paths.serverManifests()).contains("secret"));
    }

    /** Once the new file exists it is the one being edited; the old key is not consulted. */
    @Test
    void anExistingFileIsNeverOverwrittenByMigration() throws IOException {
        ModSyncPaths paths = new ModSyncPaths(dir);
        Files.createDirectories(paths.config().getParent());
        Files.writeString(paths.config(),
            "{\"manifestOverrides\":{\"old.example\":\"https://e/old.json\"}}",
            StandardCharsets.UTF_8);
        new ServerManifests(1, Map.of("new.example", "https://e/new.json"))
            .save(paths.serverManifests());

        ServerManifests servers = ServerManifests.loadOrCreate(paths);

        assertEquals("https://e/new.json", servers.urlFor("new.example", 25565));
        assertNull(servers.urlFor("old.example", 25565));
    }
}

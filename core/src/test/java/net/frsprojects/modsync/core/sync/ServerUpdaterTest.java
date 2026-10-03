package net.frsprojects.modsync.core.sync;

import com.sun.net.httpserver.HttpServer;

import net.frsprojects.modsync.core.TestFixtures;
import net.frsprojects.modsync.core.config.ServerSyncConfig;
import net.frsprojects.modsync.core.manifest.Hashes;
import net.frsprojects.modsync.core.manifest.ManifestCodec;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Policy;
import net.frsprojects.modsync.core.manifest.Side;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A dedicated server keeping its own mods in line with a manifest. */
class ServerUpdaterTest {

    @TempDir
    Path gameDir;

    private ModSyncPaths paths;
    private HttpServer server;
    private String base;
    private final List<String> progress = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        paths = new ModSyncPaths(gameDir);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void serve(String path, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private void configure(List<String> alwaysKeep) throws IOException {
        new ServerSyncConfig(1, base + "/manifest.json", alwaysKeep, List.of(), 2, false)
            .save(paths.serverConfig());
    }

    private void publish(List<ManifestEntry> entries) {
        // HttpServer contexts cannot be replaced in place, so republishing swaps the context.
        try {
            server.removeContext("/manifest.json");
        } catch (IllegalArgumentException notYetServed) {
            // First publish.
        }
        serve("/manifest.json", ManifestCodec.write(TestFixtures.manifest(entries)));
    }

    private ManifestEntry served(String path, String content, Policy policy, Side side) {
        String url = "/files/" + path;
        serve(url, content);
        return new ManifestEntry(null, path.substring(path.lastIndexOf('/') + 1), null, path,
            content.getBytes(StandardCharsets.UTF_8).length,
            Hashes.ofSha512(TestFixtures.sha512Of(content)), List.of(base + url), policy, side,
            List.of(), List.of(), null, policy.defaultSelected());
    }

    private ServerUpdater updater() {
        return new ServerUpdater(paths, "neoforge", "1.21.1", "ModSync-Test")
            .withBaseAllowlist(HostAllowlist.defaults().plusServer("127.0.0.1"));
    }

    @Test
    void withoutAManifestUrlNothingHappensButTheConfigIsCreated() {
        ServerUpdater.Result result = updater().check(progress::add);

        assertEquals(ServerUpdater.Outcome.NOT_CONFIGURED, result.outcome());
        assertTrue(Files.isRegularFile(paths.serverConfig()), "the admin needs a file to edit");
        assertFalse(Files.exists(paths.journal()));
    }

    @Test
    void installsServerEntriesSkipsClientEntriesAndAppliesOnStop() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/oldlib-1.0.jar", "old");
        TestFixtures.writeFile(gameDir, "mods/luckperms.jar", "server plugin");
        configure(List.of("mods/luckperms*.jar"));
        publish(List.of(
            served("mods/core.jar", "core", Policy.REQUIRE, Side.BOTH),
            served("mods/backup.jar", "backup", Policy.REQUIRE, Side.SERVER),
            served("mods/minimap.jar", "minimap", Policy.REQUIRE, Side.CLIENT),
            served("mods/extras.jar", "extras", Policy.OPTIONAL, Side.BOTH),
            served("mods/perf.jar", "perf", Policy.RECOMMEND, Side.BOTH)));

        ServerUpdater updater = updater();
        ServerUpdater.Result result = updater.check(progress::add);

        assertEquals(ServerUpdater.Outcome.STAGED, result.outcome(), result.summary());
        assertTrue(updater.hasPending());
        // Nothing moves while the server is running.
        assertTrue(Files.exists(gameDir.resolve("mods/oldlib-1.0.jar")));
        assertFalse(Files.exists(gameDir.resolve("mods/core.jar")));

        assertTrue(updater.applyPending().applied() > 0);

        assertEquals("core", Files.readString(gameDir.resolve("mods/core.jar")));
        assertEquals("backup", Files.readString(gameDir.resolve("mods/backup.jar")));
        assertEquals("perf", Files.readString(gameDir.resolve("mods/perf.jar")),
            "recommended files are installed: nobody is there to decline them");
        assertFalse(Files.exists(gameDir.resolve("mods/minimap.jar")), "client-only");
        assertFalse(Files.exists(gameDir.resolve("mods/extras.jar")), "optional, not chosen");
        assertFalse(Files.exists(gameDir.resolve("mods/oldlib-1.0.jar")));
        assertTrue(Files.exists(paths.quarantineDir("test-pack").resolve("mods/oldlib-1.0.jar")));
        assertTrue(Files.exists(gameDir.resolve("mods/luckperms.jar")), "kept by alwaysKeep");
        assertFalse(updater.hasPending());

        assertEquals(ServerUpdater.Outcome.UP_TO_DATE, updater.check(progress::add).outcome());
    }

    @Test
    void aClientOnlyJarOnTheServerIsMovedAside() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/minimap.jar", "minimap");
        configure(List.of());
        publish(List.of(
            served("mods/core.jar", "core", Policy.REQUIRE, Side.BOTH),
            served("mods/minimap.jar", "minimap", Policy.REQUIRE, Side.CLIENT)));

        ServerUpdater updater = updater();
        assertEquals(ServerUpdater.Outcome.STAGED, updater.check(progress::add).outcome());
        updater.applyPending();

        assertFalse(Files.exists(gameDir.resolve("mods/minimap.jar")));
    }

    @Test
    void aStagedUpdateIsDroppedWhenTheManifestNoLongerNeedsIt() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/core.jar", "core");
        configure(List.of());
        publish(List.of(served("mods/core.jar", "core-v2", Policy.REQUIRE, Side.BOTH)));

        ServerUpdater updater = updater();
        assertEquals(ServerUpdater.Outcome.STAGED, updater.check(progress::add).outcome());

        // The pack is rolled back before the server restarts.
        publish(List.of(served("mods/core.jar", "core", Policy.REQUIRE, Side.BOTH)));
        assertEquals(ServerUpdater.Outcome.UP_TO_DATE, updater.check(progress::add).outcome());

        assertFalse(updater.hasPending());
        assertNull(updater.applyPending());
        assertEquals("core", Files.readString(gameDir.resolve("mods/core.jar")));
    }

    @Test
    void aFailedDownloadStagesNothing() throws IOException {
        configure(List.of());
        ManifestEntry entry = served("mods/core.jar", "core", Policy.REQUIRE, Side.BOTH);
        server.removeContext("/files/mods/core.jar");
        serve("/files/mods/core.jar", "tampered");
        publish(List.of(entry));

        ServerUpdater updater = updater();
        ServerUpdater.Result result = updater.check(progress::add);

        assertEquals(ServerUpdater.Outcome.FAILED, result.outcome());
        assertEquals(1, result.details().size(), result.details().toString());
        assertFalse(updater.hasPending());
    }

    @Test
    void anUnreachableManifestFailsWithoutThrowing() throws IOException {
        configure(List.of());

        ServerUpdater.Result result = updater().check(progress::add);

        assertEquals(ServerUpdater.Outcome.FAILED, result.outcome());
        assertTrue(result.summary().contains("404"), result.summary());
    }

    @Test
    void theConfigRoundTrips() throws IOException {
        ServerSyncConfig config = new ServerSyncConfig(1, "https://panel.example/p/modsync/x.json",
            List.of("mods/spark-*.jar"), List.of("files.example"), 8, true);
        config.save(paths.serverConfig());

        assertEquals(config, ServerSyncConfig.load(paths.serverConfig()));
    }
}

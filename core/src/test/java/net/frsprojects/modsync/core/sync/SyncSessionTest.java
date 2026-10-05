package net.frsprojects.modsync.core.sync;

import com.sun.net.httpserver.HttpServer;

import net.frsprojects.modsync.core.TestFixtures;
import net.frsprojects.modsync.core.apply.ApplierLauncher;
import net.frsprojects.modsync.core.apply.JournalApplier;
import net.frsprojects.modsync.core.config.ModSyncConfig;
import net.frsprojects.modsync.core.diff.ActionKind;
import net.frsprojects.modsync.core.manifest.ManifestCodec;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Policy;
import net.frsprojects.modsync.core.manifest.SyncManifest;
import net.frsprojects.modsync.core.net.DownloadProgress;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client's join-time sequence against a real HTTP server: fetch, diff, download, apply. */
class SyncSessionTest {

    @TempDir
    Path gameDir;

    private ModSyncPaths paths;
    private HttpServer server;
    private String base;

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

    private void serve(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private SyncSession prepare() throws IOException {
        return SyncSession.prepare(paths, ModSyncConfig.defaults(), base + "/manifest.json",
            "fallback", "neoforge", "1.21.1", "ModSync-Test")
            .withBaseAllowlist(HostAllowlist.defaults().plusServer("127.0.0.1"));
    }

    @Test
    void fetchesDiffsDownloadsAndApplies() throws IOException {
        TestFixtures.writeFile(gameDir, "mods/old.jar", "old");
        TestFixtures.writeFile(gameDir, "mods/iris-1.8.0.jar", "my shaders");
        ManifestEntry entry = TestFixtures.entry("mods/new.jar", "new-content", Policy.REQUIRE,
            List.of(base + "/files/new.jar"));
        serve("/manifest.json", 200, ManifestCodec.write(TestFixtures.manifest(List.of(entry))));
        serve("/files/new.jar", 200, "new-content");

        SyncSession session = prepare();
        var plan = session.plan();
        assertEquals("test-pack", session.profileId());
        assertEquals(ActionKind.INSTALL, plan.actions().stream()
            .filter(a -> a.path().equals("mods/new.jar")).findFirst().orElseThrow().kind());
        assertEquals("new-content".length(), plan.downloadBytes());

        assertTrue(session.download(plan.defaultSelection(), DownloadProgress.NONE).isEmpty());
        assertTrue(session.writeJournal(plan.defaultSelection()) > 0);
        new JournalApplier(paths).applyPending();

        assertEquals("new-content", Files.readString(gameDir.resolve("mods/new.jar")));
        assertFalse(Files.exists(gameDir.resolve("mods/old.jar")));
        // Protected by the default alwaysKeep rules.
        assertTrue(Files.exists(gameDir.resolve("mods/iris-1.8.0.jar")));
        assertTrue(Files.exists(paths.quarantineDir("test-pack").resolve("mods/old.jar")));

        assertTrue(prepare().plan().isUpToDate(), "a second join must need nothing");
    }

    /** A pack that tries to write outside the sandbox is refused whole, not half-applied. */
    @Test
    void aManifestWithAnEscapingPathIsRefused() {
        SyncManifest manifest = TestFixtures.manifest(List.of(
            TestFixtures.entry("mods/fine.jar", "fine", Policy.REQUIRE),
            TestFixtures.entry("saves/world/level.dat", "evil", Policy.REQUIRE)));
        serve("/manifest.json", 200, ManifestCodec.write(manifest));

        IOException e = assertThrows(IOException.class, this::prepare);
        assertTrue(e.getMessage().contains("Refusing"), e.getMessage());
        assertFalse(Files.exists(paths.journal()));
    }

    @Test
    void aMissingManifestSaysWhatHappened() {
        serve("/manifest.json", 404, "nope");
        IOException e = assertThrows(IOException.class, this::prepare);
        assertTrue(e.getMessage().contains("404"), e.getMessage());
    }

    @Test
    void anInvalidManifestSaysWhatHappened() {
        serve("/manifest.json", 200, "[]");
        IOException e = assertThrows(IOException.class, this::prepare);
        assertTrue(e.getMessage().contains("invalid"), e.getMessage());
    }

    @Test
    void aFailedDownloadIsReportedAndNothingIsJournalled() throws IOException {
        ManifestEntry entry = TestFixtures.entry("mods/new.jar", "new-content", Policy.REQUIRE,
            List.of(base + "/files/new.jar"));
        serve("/manifest.json", 200, ManifestCodec.write(TestFixtures.manifest(List.of(entry))));
        serve("/files/new.jar", 200, "tampered");

        SyncSession session = prepare();
        assertEquals(1, session.download(session.plan().defaultSelection(),
            DownloadProgress.NONE).size());
        assertFalse(Files.exists(paths.journal()));
    }

    @Test
    void aManualFileMustBeImportedBeforeTheJournalIsWritten() throws IOException {
        ManifestEntry entry = TestFixtures.manualEntry("mods/jei.jar", "jei", Policy.REQUIRE);
        serve("/manifest.json", 200, ManifestCodec.write(TestFixtures.manifest(List.of(entry))));

        SyncSession session = prepare();
        var accepted = session.plan().defaultSelection();
        assertTrue(session.download(accepted, DownloadProgress.NONE).isEmpty(),
            "nothing for the downloader to do");
        IOException e = assertThrows(IOException.class, () -> session.writeJournal(accepted));
        assertTrue(e.getMessage().contains("by hand"), e.getMessage());
        assertFalse(Files.exists(paths.journal()));

        Path dropped = Files.writeString(gameDir.resolve("jei (1).jar"), "jei");
        assertEquals(List.of(entry), session.manualImports(accepted).offer(List.of(dropped)));
        assertTrue(session.writeJournal(accepted) > 0);
        new JournalApplier(paths).applyPending();
        assertEquals("jei", Files.readString(gameDir.resolve("mods/jei.jar")));
    }

    @Test
    void manualPagesAreCheckedAgainstTheDownloadAllowlist() throws IOException {
        serve("/manifest.json", 200, ManifestCodec.write(TestFixtures.manifest(List.of())));
        HostAllowlist allowlist = prepare().allowlist();
        assertTrue(allowlist.isAllowed("https://www.curseforge.com/minecraft/mc-mods/x/download/1"));
        assertFalse(allowlist.isAllowed("https://evil.example/download"));
    }

    /** The helper is a separate JVM with only ModSync's classes on its classpath. */
    @Test
    void theApplierRunsAsItsOwnProcess() throws Exception {
        TestFixtures.writeFile(gameDir, "mods/old.jar", "old");
        ManifestEntry entry = TestFixtures.entry("mods/new.jar", "new-content", Policy.REQUIRE,
            List.of(base + "/files/new.jar"));
        serve("/manifest.json", 200, ManifestCodec.write(TestFixtures.manifest(List.of(entry))));
        serve("/files/new.jar", 200, "new-content");
        SyncSession session = prepare();
        session.download(session.plan().defaultSelection(), DownloadProgress.NONE);
        session.writeJournal(session.plan().defaultSelection());

        // Wait for a process that has already exited, so the helper applies straight away.
        Process finished = new ProcessBuilder(javaBinary().toString(), "-version").start();
        finished.waitFor();
        Path classes = Path.of(JournalApplier.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        Process helper = new ProcessBuilder(
            ApplierLauncher.command(javaBinary(), List.of(classes), paths, finished.pid()))
            .redirectErrorStream(true).start();
        String output = new String(helper.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(helper.waitFor(60, TimeUnit.SECONDS));

        assertEquals(0, helper.exitValue(), output);
        assertEquals("new-content", Files.readString(gameDir.resolve("mods/new.jar")));
        assertFalse(Files.exists(paths.journal()));
    }

    private static Path javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }
}

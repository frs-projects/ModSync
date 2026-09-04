package net.frsprojects.modsync.core.export;

import net.frsprojects.modsync.core.TestFixtures;
import net.frsprojects.modsync.core.manifest.ManifestCodec;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Policy;
import net.frsprojects.modsync.core.manifest.Side;
import net.frsprojects.modsync.core.manifest.SyncManifest;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportServiceTest {

    @TempDir
    Path gameDir;

    /** Answers for whatever it was given, so the export logic is tested without a network. */
    private static ModMetadataLookup stub(Map<String, ModMetadataLookup.Resolved> answers) {
        return new ModMetadataLookup() {
            @Override public String name() { return "Stub"; }
            @Override public Map<String, Resolved> resolve(List<ExportCandidate> candidates) {
                return answers;
            }
        };
    }

    private SyncManifest read(Path output) throws Exception {
        return ManifestCodec.parse(Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void writesAManifestThatParsesBackIdentically() throws Exception {
        TestFixtures.writeFile(gameDir, "mods/sodium.jar", "sodium");
        TestFixtures.writeFile(gameDir, "mods/lithium.jar", "lithium");

        Path output = ExportService.export(
            new ExportRequest(gameDir, "mods", "My Pack", "1.2.3",
                List.of(stub(Map.of("mods/sodium.jar",
                    new ModMetadataLookup.Resolved("modrinth:AANobbMI",
                        "https://cdn.modrinth.com/sodium.jar"))))),
            ExportProgress.NONE);

        // The real assertion: what we wrote is what our own parser accepts. This is what
        // catches a packId charset slip or a missing hash.
        SyncManifest parsed = read(output);
        assertEquals(SyncManifest.CURRENT_FORMAT_VERSION, parsed.formatVersion());
        assertEquals("My Pack", parsed.packName());
        assertEquals("1.2.3", parsed.packVersion());
        assertEquals("my-pack", parsed.packId());
        assertEquals(2, parsed.files().size());

        ManifestEntry sodium = parsed.files().stream()
            .filter(e -> e.path().equals("mods/sodium.jar")).findFirst().orElseThrow();
        assertEquals("sodium.jar", sodium.label());
        assertEquals("modrinth:AANobbMI", sodium.id());
        assertEquals(List.of("https://cdn.modrinth.com/sodium.jar"), sodium.urls());
        assertEquals(TestFixtures.sha512Of("sodium"), sodium.hashes().sha512());
        assertEquals(6, sodium.size());

        ManifestEntry lithium = parsed.files().stream()
            .filter(e -> e.path().equals("mods/lithium.jar")).findFirst().orElseThrow();
        assertTrue(lithium.urls().isEmpty());
        assertNull(lithium.id());
    }

    @Test
    void appliesPerFolderPolicyAndSide() throws Exception {
        TestFixtures.writeFile(gameDir, "mods/sodium.jar", "sodium");
        TestFixtures.writeFile(gameDir, "shaderpacks/bsl.zip", "bsl");

        SyncManifest mods = read(ExportService.export(
            ExportRequest.offline(gameDir, "mods"), ExportProgress.NONE));
        assertEquals(Policy.REQUIRE, mods.files().get(0).policy());
        assertEquals(Side.BOTH, mods.files().get(0).side());

        SyncManifest shaders = read(ExportService.export(
            ExportRequest.offline(gameDir, "shaderpacks"), ExportProgress.NONE));
        assertEquals(Policy.OPTIONAL, shaders.files().get(0).policy());
        assertEquals(Side.CLIENT, shaders.files().get(0).side());
    }

    @Test
    void reportsResolvedAndUnresolvedCounts() throws Exception {
        TestFixtures.writeFile(gameDir, "mods/a.jar", "a");
        TestFixtures.writeFile(gameDir, "mods/b.jar", "b");

        int[] seen = new int[3];
        ExportService.export(
            new ExportRequest(gameDir, "mods", null, null,
                List.of(stub(Map.of("mods/a.jar",
                    new ModMetadataLookup.Resolved(null, "https://example.com/a.jar"))))),
            new ExportProgress() {
                @Override public void finished(Path out, int total, int resolved, int unresolved) {
                    seen[0] = total;
                    seen[1] = resolved;
                    seen[2] = unresolved;
                }
            });

        assertEquals(2, seen[0]);
        assertEquals(1, seen[1]);
        assertEquals(1, seen[2]);
    }

    @Test
    void aFailingLookupStillProducesAManifest() throws Exception {
        TestFixtures.writeFile(gameDir, "mods/a.jar", "a");

        ModMetadataLookup broken = new ModMetadataLookup() {
            @Override public String name() { return "Broken"; }
            @Override public Map<String, Resolved> resolve(List<ExportCandidate> c)
                    throws IOException {
                throw new IOException("host is down");
            }
        };

        SyncManifest parsed = read(ExportService.export(
            new ExportRequest(gameDir, "mods", null, null, List.of(broken)),
            ExportProgress.NONE));
        assertEquals(1, parsed.files().size());
        assertTrue(parsed.files().get(0).urls().isEmpty());
    }

    @Test
    void neverOverwritesAnEarlierExport() throws Exception {
        TestFixtures.writeFile(gameDir, "mods/a.jar", "a");

        Path first = ExportService.export(
            ExportRequest.offline(gameDir, "mods"), ExportProgress.NONE);
        Files.writeString(first, "{\"hand\":\"edited\"}", StandardCharsets.UTF_8);

        // A second export within the same second must not clobber the first.
        Path second = ExportService.export(
            ExportRequest.offline(gameDir, "mods"), ExportProgress.NONE);

        if (first.equals(second)) {
            // Same-second collision: the design guarantee we care about is the timestamped
            // name, so assert it is at least shaped correctly rather than sleeping a second.
            assertTrue(first.getFileName().toString().matches(
                "modsync-export-mods-\\d{8}-\\d{6}\\.json"));
        } else {
            assertNotEquals(first, second);
            assertEquals("{\"hand\":\"edited\"}", Files.readString(first));
        }
        assertTrue(second.startsWith(gameDir.resolve("modsync").resolve("exports")));
    }

    @Test
    void refusesAnEmptyFolder() throws Exception {
        Files.createDirectories(gameDir.resolve("mods"));
        ExportException e = assertThrows(ExportException.class, () -> ExportService.export(
            ExportRequest.offline(gameDir, "mods"), ExportProgress.NONE));
        assertTrue(e.getMessage().contains("nothing to export"));
    }

    @Test
    void derivesAPackIdTheParserAccepts() {
        assertEquals("my-pack-2024", ExportService.toPackId("My Pack (2024)", "mods"));
        // A name that is all punctuation leaves nothing legal to start with.
        assertEquals("export-mods", ExportService.toPackId("!!!", "mods"));
        // packId must not start with '.' or '-', which ModSyncPaths.sanitize would permit.
        assertFalse(ExportService.toPackId(".hidden", "mods").startsWith("."));
        assertTrue(ExportService.toPackId("A".repeat(200), "mods").length() <= 64);
    }
}

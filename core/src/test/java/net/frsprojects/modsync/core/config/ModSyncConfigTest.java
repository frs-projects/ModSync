package net.frsprojects.modsync.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModSyncConfigTest {

    @TempDir
    Path dir;

    @Test
    void aMissingFileYieldsDefaults() throws IOException {
        ModSyncConfig config = ModSyncConfig.load(dir.resolve("modsync.json"));
        assertEquals(ModSyncConfig.defaults(), config);
    }

    /** A first-time player must not lose their shaders before finding the settings. */
    @Test
    void defaultsProtectTheUsualClientOnlyMods() {
        List<String> keep = ModSyncConfig.defaults().alwaysKeep();
        assertTrue(keep.stream().anyMatch(g -> g.contains("iris")));
        assertTrue(keep.contains("shaderpacks/**"));
    }

    @Test
    void roundTripsThroughDisk() throws IOException {
        ModSyncConfig config = new ModSyncConfig(1,
            List.of("mods/keep-*.jar"),
            List.of("my-cdn.example"),
            8,
            Map.of("mc.example.net:25565", "https://example.net/manifest.json"),
            false);

        Path file = dir.resolve("modsync.json");
        config.save(file);
        assertEquals(config, ModSyncConfig.load(file));
    }

    @Test
    void parallelDownloadsIsClamped() throws IOException {
        Path file = dir.resolve("modsync.json");
        Files.writeString(file, "{\"parallelDownloads\":9999}", StandardCharsets.UTF_8);
        assertEquals(16, ModSyncConfig.load(file).parallelDownloads());

        Files.writeString(file, "{\"parallelDownloads\":-5}", StandardCharsets.UTF_8);
        assertEquals(1, ModSyncConfig.load(file).parallelDownloads());
    }

    @Test
    void manifestOverridesAreLookedUpByHostAndPort() throws IOException {
        Path file = dir.resolve("modsync.json");
        Files.writeString(file,
            "{\"manifestOverrides\":{\"mc.example.net:25565\":\"https://e/m.json\"}}",
            StandardCharsets.UTF_8);

        ModSyncConfig config = ModSyncConfig.load(file);
        assertEquals("https://e/m.json", config.manifestOverrideFor("mc.example.net:25565"));
        assertNull(config.manifestOverrideFor("other.example:25565"));
    }

    @Test
    void aPartialFileKeepsDefaultsForEverythingElse() throws IOException {
        Path file = dir.resolve("modsync.json");
        Files.writeString(file, "{\"autoProbe\":false}", StandardCharsets.UTF_8);

        ModSyncConfig config = ModSyncConfig.load(file);
        assertEquals(false, config.autoProbe());
        assertEquals(ModSyncConfig.defaults().alwaysKeep(), config.alwaysKeep());
    }

    @Test
    void aCorruptFileFailsLoudlyRatherThanSilentlyDroppingKeepRules() throws IOException {
        Path file = dir.resolve("modsync.json");
        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
        // Silently falling back to defaults here would quietly discard the player's
        // alwaysKeep rules and then quarantine the mods those rules protect.
        assertThrows(IOException.class, () -> ModSyncConfig.load(file));
    }
}

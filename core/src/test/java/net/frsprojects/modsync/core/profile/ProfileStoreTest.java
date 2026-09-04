package net.frsprojects.modsync.core.profile;

import net.frsprojects.modsync.core.TestFixtures;
import net.frsprojects.modsync.core.diff.LocalFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileStoreTest {

    @TempDir
    Path gameDir;

    private ModSyncPaths paths;
    private ProfileStore store;

    @BeforeEach
    void setUp() throws IOException {
        paths = new ModSyncPaths(gameDir);
        paths.createDirectories();
        store = new ProfileStore(paths);
    }

    private static LocalFile file(String path, String content) {
        return new LocalFile(path, content.length(), 0L, TestFixtures.sha512Of(content));
    }

    @Test
    void roundTripsAProfile() throws IOException {
        Profile saved = Profile.fromManifest("turbo-smp",
            TestFixtures.manifest(List.of()),
            List.of(file("mods/a.jar", "a"), file("mods/b.jar", "b")));
        store.save(saved);

        Profile loaded = store.load("turbo-smp").orElseThrow();
        assertEquals(saved.profileId(), loaded.profileId());
        assertEquals(saved.packId(), loaded.packId());
        assertEquals(saved.files(), loaded.files());
    }

    @Test
    void anUnknownProfileIsEmptyNotAnError() throws IOException {
        assertEquals(Optional.empty(), store.load("never-seen"));
    }

    @Test
    void listsSavedProfiles() throws IOException {
        store.save(Profile.base(List.of(file("mods/mine.jar", "mine"))));
        store.save(Profile.fromManifest("turbo-smp",
            TestFixtures.manifest(List.of()), List.of(file("mods/a.jar", "a"))));

        assertEquals(List.of(ModSyncPaths.BASE_PROFILE, "turbo-smp"), store.listProfileIds());
    }

    /** Pruning must never delete a blob some other profile still needs. */
    @Test
    void referencedHashesSpanEveryProfile() throws IOException {
        store.save(Profile.base(List.of(file("mods/mine.jar", "mine"))));
        store.save(Profile.fromManifest("pack-a",
            TestFixtures.manifest(List.of()), List.of(file("mods/a.jar", "a"))));

        Set<String> referenced = store.allReferencedHashes();
        assertTrue(referenced.contains(TestFixtures.sha512Of("mine")));
        assertTrue(referenced.contains(TestFixtures.sha512Of("a")));
        assertEquals(2, referenced.size());
    }

    @Test
    void tracksWhichProfileIsActive() throws IOException {
        assertEquals(Optional.empty(), store.activeProfileId());
        store.setActiveProfile("turbo-smp");
        assertEquals(Optional.of("turbo-smp"), store.activeProfileId());
        store.setActiveProfile(ModSyncPaths.BASE_PROFILE);
        assertEquals(Optional.of(ModSyncPaths.BASE_PROFILE), store.activeProfileId());
    }

    @Test
    void aCorruptActiveMarkerReadsAsUnknownRatherThanFailing() throws IOException {
        Files.createDirectories(paths.activeState().getParent());
        Files.writeString(paths.activeState(), "{ not json", StandardCharsets.UTF_8);
        assertEquals(Optional.empty(), store.activeProfileId());
    }

    @Test
    void aCorruptProfileFailsLoudly() throws IOException {
        Path file = paths.profileFile("broken");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> store.load("broken"));
    }

    @Test
    void aProfileFromANewerModSyncIsRefused() throws IOException {
        Path file = paths.profileFile("future");
        Files.createDirectories(file.getParent());
        Files.writeString(file,
            "{\"formatVersion\":99,\"profileId\":\"future\",\"files\":[]}",
            StandardCharsets.UTF_8);

        IOException e = assertThrows(IOException.class, () -> store.load("future"));
        assertTrue(e.getMessage().contains("newer ModSync"));
    }

    @Test
    void theBaseProfileCapturesThePlayersOwnMods() throws IOException {
        Profile base = Profile.base(List.of(file("mods/iris.jar", "shaders")));
        store.save(base);

        Profile loaded = store.load(ModSyncPaths.BASE_PROFILE).orElseThrow();
        assertEquals(1, loaded.files().size());
        assertEquals("mods/iris.jar", loaded.files().get(0).path());
        assertFalse(loaded.packId() != null, "the base profile belongs to no pack");
    }
}

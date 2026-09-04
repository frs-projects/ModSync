package net.frsprojects.modsync.core.manifest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestCodecTest {

    private static final String SHA512 = "a".repeat(128);
    private static final String SHA1 = "b".repeat(40);

    private static String manifestWith(String entryJson) {
        return "{\"formatVersion\":1,\"packId\":\"test-pack\",\"files\":[" + entryJson + "]}";
    }

    private static String fullEntry() {
        return "{"
            + "\"id\":\"modrinth:sodium\","
            + "\"label\":\"Sodium\","
            + "\"desc\":\"Rendering optimisation\","
            + "\"path\":\"mods/sodium-0.6.13.jar\","
            + "\"size\":918273,"
            + "\"hashes\":{\"sha512\":\"" + SHA512 + "\",\"sha1\":\"" + SHA1 + "\"},"
            + "\"urls\":[\"https://cdn.modrinth.com/a.jar\",\"https://mirror/a.jar\"],"
            + "\"policy\":\"require\","
            + "\"side\":\"client\","
            + "\"loaders\":[\"neoforge\",\"fabric\"],"
            + "\"mcVersions\":[\"1.21.1\"],"
            + "\"group\":\"Performance\""
            + "}";
    }

    @Test
    void parsesAFullEntry() throws Exception {
        SyncManifest m = ManifestCodec.parse(manifestWith(fullEntry()));

        assertEquals(1, m.formatVersion());
        assertEquals("test-pack", m.packId());
        assertEquals(UnlistedPolicy.QUARANTINE, m.unlistedPolicy());
        assertEquals(1, m.files().size());

        ManifestEntry e = m.files().get(0);
        assertEquals("modrinth:sodium", e.id());
        assertEquals("mods/sodium-0.6.13.jar", e.path());
        assertEquals("sodium-0.6.13.jar", e.fileName());
        assertEquals("mods", e.parentDir());
        assertEquals(918273L, e.size());
        assertEquals(SHA512, e.hashes().sha512());
        assertEquals(List.of("https://cdn.modrinth.com/a.jar", "https://mirror/a.jar"), e.urls());
        assertEquals(Policy.REQUIRE, e.policy());
        assertEquals(Side.CLIENT, e.side());
        assertEquals("Performance", e.group());
    }

    @Test
    void roundTripsThroughWrite() throws Exception {
        SyncManifest original = ManifestCodec.parse(manifestWith(fullEntry()));
        SyncManifest reparsed = ManifestCodec.parse(ManifestCodec.write(original));
        assertEquals(original, reparsed);
    }

    @Test
    void rejectsTheBareArraySketchFormat() {
        ManifestException e = assertThrows(ManifestException.class,
            () -> ManifestCodec.parse("[{\"path\":\"mods/a.jar\"}]"));
        assertTrue(e.getMessage().contains("JSON object"));
    }

    @Test
    void rejectsAFutureFormatVersion() {
        ManifestException e = assertThrows(ManifestException.class,
            () -> ManifestCodec.parse("{\"formatVersion\":99,\"files\":[]}"));
        assertTrue(e.getMessage().contains("Update ModSync"));
    }

    @Test
    void rejectsAMissingFilesArray() {
        assertThrows(ManifestException.class,
            () -> ManifestCodec.parse("{\"formatVersion\":1}"));
    }

    @Test
    void enumsAreCaseInsensitive() throws Exception {
        SyncManifest m = ManifestCodec.parse(manifestWith(
            "{\"path\":\"mods/a.jar\",\"policy\":\"FoRbId\",\"side\":\"Both\"}"));
        assertEquals(Policy.FORBID, m.files().get(0).policy());
        assertEquals(Side.BOTH, m.files().get(0).side());
    }

    @Test
    void rejectsAnUnknownEnumValue() {
        assertThrows(ManifestException.class, () -> ManifestCodec.parse(manifestWith(
            "{\"path\":\"mods/a.jar\",\"hashes\":{\"sha512\":\"" + SHA512 + "\"},"
                + "\"policy\":\"maybe\"}")));
    }

    /** The original README sketch used {@code required: true/false}. */
    @Test
    void acceptsTheLegacyRequiredBoolean() throws Exception {
        SyncManifest req = ManifestCodec.parse(manifestWith(
            "{\"path\":\"mods/a.jar\",\"required\":true,\"hashes\":{\"sha512\":\""
                + SHA512 + "\"}}"));
        assertEquals(Policy.REQUIRE, req.files().get(0).policy());

        SyncManifest opt = ManifestCodec.parse(manifestWith(
            "{\"path\":\"mods/a.jar\",\"required\":false,\"hashes\":{\"sha512\":\""
                + SHA512 + "\"}}"));
        assertEquals(Policy.OPTIONAL, opt.files().get(0).policy());
    }

    /** The sketch also used a singular {@code url}. */
    @Test
    void acceptsTheLegacySingularUrlAndMergesItWithMirrors() throws Exception {
        SyncManifest m = ManifestCodec.parse(manifestWith(
            "{\"path\":\"mods/a.jar\",\"hashes\":{\"sha512\":\"" + SHA512 + "\"},"
                + "\"url\":\"https://a/1.jar\",\"urls\":[\"https://b/1.jar\",\"https://a/1.jar\"]}"));
        // The duplicate is collapsed, and the singular URL keeps priority.
        assertEquals(List.of("https://a/1.jar", "https://b/1.jar"), m.files().get(0).urls());
    }

    @Test
    void requiresASha512ForEverythingButForbid() {
        assertThrows(ManifestException.class,
            () -> ManifestCodec.parse(manifestWith("{\"path\":\"mods/a.jar\"}")));
    }

    @Test
    void forbidEntriesNeedNoHashBecauseThePathIsEnough() throws Exception {
        SyncManifest m = ManifestCodec.parse(manifestWith(
            "{\"path\":\"mods/cheat.jar\",\"policy\":\"forbid\"}"));
        assertNull(m.files().get(0).hashes().sha512());
    }

    @Test
    void rejectsAMalformedHash() {
        assertThrows(ManifestException.class, () -> ManifestCodec.parse(manifestWith(
            "{\"path\":\"mods/a.jar\",\"hashes\":{\"sha512\":\"nothex\"}}")));
        assertThrows(ManifestException.class, () -> ManifestCodec.parse(manifestWith(
            "{\"path\":\"mods/a.jar\",\"hashes\":{\"sha512\":\"" + "z".repeat(128) + "\"}}")));
    }

    @Test
    void normalizesPathSeparatorsAndRejectsTraversal() throws Exception {
        SyncManifest m = ManifestCodec.parse(manifestWith(
            "{\"path\":\"mods\\\\sub\\\\a.jar\",\"hashes\":{\"sha512\":\"" + SHA512 + "\"}}"));
        assertEquals("mods/sub/a.jar", m.files().get(0).path());

        assertThrows(ManifestException.class, () -> ManifestCodec.parse(manifestWith(
            "{\"path\":\"mods/../../evil.jar\",\"hashes\":{\"sha512\":\"" + SHA512 + "\"}}")));
        assertThrows(ManifestException.class, () -> ManifestCodec.parse(manifestWith(
            "{\"path\":\"/etc/passwd\",\"hashes\":{\"sha512\":\"" + SHA512 + "\"}}")));
        assertThrows(ManifestException.class, () -> ManifestCodec.parse(manifestWith(
            "{\"path\":\"C:/evil.jar\",\"hashes\":{\"sha512\":\"" + SHA512 + "\"}}")));
    }

    @Test
    void rejectsAnInvalidPackIdBecauseItBecomesADirectoryName() {
        assertThrows(ManifestException.class,
            () -> ManifestCodec.parse("{\"formatVersion\":1,\"packId\":\"../evil\",\"files\":[]}"));
        assertThrows(ManifestException.class,
            () -> ManifestCodec.parse("{\"formatVersion\":1,\"packId\":\"a/b\",\"files\":[]}"));
    }

    @Test
    void rejectsAnAbsurdlyLargeDeclaredSize() {
        assertThrows(ManifestException.class, () -> ManifestCodec.parse(manifestWith(
            "{\"path\":\"mods/a.jar\",\"size\":999999999999999,\"hashes\":{\"sha512\":\""
                + SHA512 + "\"}}")));
    }

    @Test
    void filtersByLoaderVersionAndSide() throws Exception {
        SyncManifest m = ManifestCodec.parse(manifestWith(fullEntry()));

        assertEquals(1, m.forClient("neoforge", "1.21.1").size());
        assertEquals(1, m.forClient("fabric", "1.21.1").size());
        assertTrue(m.forClient("forge", "1.21.1").isEmpty(), "loader not listed");
        assertTrue(m.forClient("neoforge", "1.20.1").isEmpty(), "version not listed");
        assertTrue(m.forServer("neoforge", "1.21.1").isEmpty(), "entry is client-side");
    }

    @Test
    void emptyLoaderAndVersionListsMeanAny() throws Exception {
        SyncManifest m = ManifestCodec.parse(manifestWith(
            "{\"path\":\"mods/a.jar\",\"hashes\":{\"sha512\":\"" + SHA512 + "\"}}"));
        assertEquals(1, m.forClient("anything", "9.9.9").size());
        assertEquals(1, m.forServer("anything", "9.9.9").size());
    }

    @Test
    void defaultSelectionFollowsPolicy() throws Exception {
        SyncManifest m = ManifestCodec.parse("{\"formatVersion\":1,\"files\":["
            + "{\"path\":\"mods/r.jar\",\"policy\":\"require\",\"hashes\":{\"sha512\":\""
            + SHA512 + "\"}},"
            + "{\"path\":\"mods/c.jar\",\"policy\":\"recommend\",\"hashes\":{\"sha512\":\""
            + SHA512 + "\"}},"
            + "{\"path\":\"mods/o.jar\",\"policy\":\"optional\",\"hashes\":{\"sha512\":\""
            + SHA512 + "\"}}]}");
        assertTrue(m.files().get(0).defaultEnabled());
        assertTrue(m.files().get(1).defaultEnabled());
        assertFalse(m.files().get(2).defaultEnabled());
    }

    @Test
    void labelFallsBackToTheFileName() throws Exception {
        SyncManifest m = ManifestCodec.parse(manifestWith(
            "{\"path\":\"mods/sodium.jar\",\"hashes\":{\"sha512\":\"" + SHA512 + "\"}}"));
        assertEquals("sodium.jar", m.files().get(0).label());
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(ManifestException.class, () -> ManifestCodec.parse("{not json"));
    }
}

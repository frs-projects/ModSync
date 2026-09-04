package net.frsprojects.modsync.core.manifest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.Reader;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes {@link SyncManifest}.
 *
 * <p>Parsing is done field by field off a {@code JsonObject} rather than by reflection.
 * A manifest arrives from a remote server, so the parser is the trust boundary: it has to
 * reject hostile input with a message an admin can act on, and it has to enforce hard
 * limits so a malicious manifest cannot exhaust memory before validation ever runs.
 */
public final class ManifestCodec {

    /** Refuse absurd manifests outright rather than allocating for them. */
    public static final int MAX_FILES = 10_000;
    public static final int MAX_PATH_LENGTH = 512;
    public static final int MAX_URLS_PER_ENTRY = 16;
    public static final int MAX_URL_LENGTH = 2_048;
    public static final int MAX_TEXT_LENGTH = 4_096;
    /** 16 GiB: larger than any legitimate single game file, small enough to catch nonsense. */
    public static final long MAX_FILE_SIZE = 16L * 1024 * 1024 * 1024;

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private ManifestCodec() {}

    public static SyncManifest parse(Reader reader) throws ManifestException {
        try {
            return parse(JsonParser.parseReader(reader));
        } catch (JsonParseException e) {
            throw new ManifestException("Manifest is not valid JSON: " + e.getMessage(), e);
        }
    }

    public static SyncManifest parse(String json) throws ManifestException {
        try {
            return parse(JsonParser.parseString(json));
        } catch (JsonParseException e) {
            throw new ManifestException("Manifest is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static SyncManifest parse(JsonElement root) throws ManifestException {
        if (root == null || !root.isJsonObject()) {
            throw new ManifestException(
                "Manifest must be a JSON object. A bare array is the pre-v1 sketch format "
                    + "and is not supported.");
        }
        JsonObject o = root.getAsJsonObject();

        int formatVersion = reqInt(o, "formatVersion");
        if (formatVersion > SyncManifest.CURRENT_FORMAT_VERSION) {
            throw new ManifestException(
                "Manifest declares formatVersion " + formatVersion + " but this ModSync build "
                    + "understands at most " + SyncManifest.CURRENT_FORMAT_VERSION
                    + ". Update ModSync.");
        }
        if (formatVersion < 1) {
            throw new ManifestException("formatVersion must be >= 1, got " + formatVersion);
        }

        String packId = optString(o, "packId", null);
        if (packId != null) {
            validatePackId(packId);
        }

        JsonArray filesArray = o.has("files") && o.get("files").isJsonArray()
            ? o.getAsJsonArray("files")
            : null;
        if (filesArray == null) {
            throw new ManifestException("Manifest is missing the 'files' array");
        }
        if (filesArray.size() > MAX_FILES) {
            throw new ManifestException(
                "Manifest lists " + filesArray.size() + " files, over the limit of " + MAX_FILES);
        }

        List<ManifestEntry> files = new ArrayList<>(filesArray.size());
        for (int i = 0; i < filesArray.size(); i++) {
            JsonElement el = filesArray.get(i);
            if (!el.isJsonObject()) {
                throw new ManifestException("files[" + i + "] is not an object");
            }
            files.add(parseEntry(el.getAsJsonObject(), "files[" + i + "]"));
        }

        return new SyncManifest(
            formatVersion,
            packId,
            optText(o, "packName", packId != null ? packId : "Unnamed pack"),
            optText(o, "packVersion", "0"),
            optInstant(o, "generatedAt"),
            optEnum(o, "unlistedPolicy", UnlistedPolicy.class, UnlistedPolicy.QUARANTINE),
            List.copyOf(files));
    }

    private static ManifestEntry parseEntry(JsonObject o, String where) throws ManifestException {
        String path = normalizePath(reqString(o, "path", where), where);

        Policy policy = optEnum(o, "policy", Policy.class, null);
        if (policy == null) {
            // Tolerate the original sketch's boolean so early manifests keep working.
            Boolean required = optBoolean(o, "required");
            policy = required == null ? Policy.REQUIRE
                : (required ? Policy.REQUIRE : Policy.OPTIONAL);
        }

        Hashes hashes = parseHashes(o, where, policy);

        long size = optLong(o, "size", -1L);
        if (size > MAX_FILE_SIZE) {
            throw new ManifestException(
                where + ".size is " + size + " bytes, over the limit of " + MAX_FILE_SIZE);
        }
        if (size < -1L) {
            throw new ManifestException(where + ".size cannot be negative");
        }

        List<String> urls = parseUrls(o, where);

        return new ManifestEntry(
            optText(o, "id", null),
            optText(o, "label", fileNameOf(path)),
            optText(o, "desc", null),
            path,
            size,
            hashes,
            urls,
            policy,
            optEnum(o, "side", Side.class, Side.BOTH),
            parseStringList(o, "loaders", where),
            parseStringList(o, "mcVersions", where),
            optText(o, "group", null),
            optBooleanOr(o, "defaultEnabled", policy.defaultSelected()));
    }

    private static Hashes parseHashes(JsonObject o, String where, Policy policy)
            throws ManifestException {
        Hashes hashes;
        if (o.has("hashes") && o.get("hashes").isJsonObject()) {
            JsonObject h = o.getAsJsonObject("hashes");
            hashes = new Hashes(optString(h, "sha512", null), optString(h, "sha1", null));
        } else {
            hashes = new Hashes(null, null);
        }
        // A FORBID entry names a file to remove, which may be identified by path alone,
        // so it is the one policy that does not require a hash to be actionable.
        if (policy != Policy.FORBID) {
            hashes.validate(where);
        } else if (hashes.sha512() != null || hashes.sha1() != null) {
            hashes.validate(where);
        }
        return hashes;
    }

    private static List<String> parseUrls(JsonObject o, String where) throws ManifestException {
        List<String> urls = new ArrayList<>();
        // Accept the singular `url` from the original sketch alongside the mirror list.
        String single = optString(o, "url", null);
        if (single != null && !single.isBlank()) {
            urls.add(single.trim());
        }
        if (o.has("urls") && o.get("urls").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("urls")) {
                if (!el.isJsonPrimitive()) {
                    throw new ManifestException(where + ".urls contains a non-string entry");
                }
                String u = el.getAsString().trim();
                if (!u.isEmpty() && !urls.contains(u)) {
                    urls.add(u);
                }
            }
        }
        if (urls.size() > MAX_URLS_PER_ENTRY) {
            throw new ManifestException(
                where + ".urls has " + urls.size() + " entries, over the limit of "
                    + MAX_URLS_PER_ENTRY);
        }
        for (String u : urls) {
            if (u.length() > MAX_URL_LENGTH) {
                throw new ManifestException(where + ".urls contains an over-long URL");
            }
        }
        return List.copyOf(urls);
    }

    /**
     * Normalizes a manifest path to forward slashes and rejects the obvious traversal
     * shapes. This is a syntax check only — {@code PathSandbox} still has to decide
     * whether the resulting path is somewhere the client is willing to write.
     */
    static String normalizePath(String raw, String where) throws ManifestException {
        if (!raw.equals(raw.strip())) {
            // Trimming would silently retarget "mods/a.jar " to a different file on
            // Windows, so a padded path is an error rather than something to clean up.
            throw new ManifestException(
                where + ".path has leading or trailing whitespace: '" + raw + "'");
        }
        String p = raw.replace('\\', '/');
        if (p.isEmpty()) {
            throw new ManifestException(where + ".path is empty");
        }
        if (p.length() > MAX_PATH_LENGTH) {
            throw new ManifestException(
                where + ".path is " + p.length() + " characters, over the limit of "
                    + MAX_PATH_LENGTH);
        }
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) {
            throw new ManifestException(
                where + ".path must be relative to the game directory, got '" + raw + "'");
        }
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        if (p.endsWith("/")) {
            throw new ManifestException(where + ".path must name a file, not a directory");
        }
        for (String segment : p.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new ManifestException(
                    where + ".path must not contain '.' or '..' segments, got '" + raw + "'");
            }
        }
        return p;
    }

    private static void validatePackId(String packId) throws ManifestException {
        // packId becomes a directory name on the client, so keep it to a boring charset.
        if (!packId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new ManifestException(
                "packId '" + packId + "' must be 1-64 characters of letters, digits, '.', '_' "
                    + "or '-', starting with a letter or digit");
        }
    }

    private static String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    // ── JSON helpers ────────────────────────────────────────────────────────────

    private static JsonPrimitive primitive(JsonObject o, String key) {
        if (!o.has(key)) {
            return null;
        }
        JsonElement el = o.get(key);
        return el.isJsonPrimitive() ? el.getAsJsonPrimitive() : null;
    }

    private static String reqString(JsonObject o, String key, String where)
            throws ManifestException {
        String v = optString(o, key, null);
        if (v == null) {
            throw new ManifestException(where + "." + key + " is required");
        }
        return v;
    }

    private static String optString(JsonObject o, String key, String fallback) {
        JsonPrimitive p = primitive(o, key);
        return p != null && p.isString() ? p.getAsString() : fallback;
    }

    private static String optText(JsonObject o, String key, String fallback) {
        String v = optString(o, key, null);
        if (v == null) {
            return fallback;
        }
        v = v.trim();
        if (v.isEmpty()) {
            return fallback;
        }
        return v.length() > MAX_TEXT_LENGTH ? v.substring(0, MAX_TEXT_LENGTH) : v;
    }

    private static int reqInt(JsonObject o, String key) throws ManifestException {
        JsonPrimitive p = primitive(o, key);
        if (p == null || !p.isNumber()) {
            throw new ManifestException(key + " is required and must be a number");
        }
        return p.getAsInt();
    }

    private static long optLong(JsonObject o, String key, long fallback) {
        JsonPrimitive p = primitive(o, key);
        return p != null && p.isNumber() ? p.getAsLong() : fallback;
    }

    private static Boolean optBoolean(JsonObject o, String key) {
        JsonPrimitive p = primitive(o, key);
        return p != null && p.isBoolean() ? p.getAsBoolean() : null;
    }

    private static boolean optBooleanOr(JsonObject o, String key, boolean fallback) {
        Boolean v = optBoolean(o, key);
        return v == null ? fallback : v;
    }

    private static <E extends Enum<E>> E optEnum(
            JsonObject o, String key, Class<E> type, E fallback) throws ManifestException {
        String raw = optString(o, key, null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ManifestException(
                key + " '" + raw + "' is not one of " + java.util.Arrays.toString(
                    type.getEnumConstants()).toLowerCase(Locale.ROOT));
        }
    }

    private static Instant optInstant(JsonObject o, String key) {
        String raw = optString(o, key, null);
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            // Purely informational; a bad timestamp must not sink an otherwise valid manifest.
            return null;
        }
    }

    private static List<String> parseStringList(JsonObject o, String key, String where)
            throws ManifestException {
        if (!o.has(key)) {
            return List.of();
        }
        JsonElement el = o.get(key);
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            return List.of(el.getAsString().trim());
        }
        if (!el.isJsonArray()) {
            throw new ManifestException(where + "." + key + " must be a string or array of strings");
        }
        List<String> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new ManifestException(where + "." + key + " contains a non-string entry");
            }
            String s = item.getAsString().trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    // ── Writing ─────────────────────────────────────────────────────────────────

    public static String write(SyncManifest manifest) {
        JsonObject o = new JsonObject();
        o.addProperty("formatVersion", manifest.formatVersion());
        if (manifest.packId() != null) {
            o.addProperty("packId", manifest.packId());
        }
        o.addProperty("packName", manifest.packName());
        o.addProperty("packVersion", manifest.packVersion());
        if (manifest.generatedAt() != null) {
            o.addProperty("generatedAt", manifest.generatedAt().toString());
        }
        o.addProperty("unlistedPolicy", lower(manifest.unlistedPolicy()));

        JsonArray files = new JsonArray();
        for (ManifestEntry e : manifest.files()) {
            files.add(writeEntry(e));
        }
        o.add("files", files);
        return GSON.toJson(o);
    }

    private static JsonObject writeEntry(ManifestEntry e) {
        JsonObject j = new JsonObject();
        if (e.id() != null) {
            j.addProperty("id", e.id());
        }
        j.addProperty("label", e.label());
        if (e.desc() != null) {
            j.addProperty("desc", e.desc());
        }
        j.addProperty("path", e.path());
        if (e.size() >= 0) {
            j.addProperty("size", e.size());
        }

        JsonObject hashes = new JsonObject();
        if (e.hashes().sha512() != null) {
            hashes.addProperty("sha512", e.hashes().sha512());
        }
        if (e.hashes().sha1() != null) {
            hashes.addProperty("sha1", e.hashes().sha1());
        }
        if (hashes.size() > 0) {
            j.add("hashes", hashes);
        }

        if (!e.urls().isEmpty()) {
            JsonArray urls = new JsonArray();
            e.urls().forEach(urls::add);
            j.add("urls", urls);
        }

        j.addProperty("policy", lower(e.policy()));
        j.addProperty("side", lower(e.side()));
        if (!e.loaders().isEmpty()) {
            JsonArray a = new JsonArray();
            e.loaders().forEach(a::add);
            j.add("loaders", a);
        }
        if (!e.mcVersions().isEmpty()) {
            JsonArray a = new JsonArray();
            e.mcVersions().forEach(a::add);
            j.add("mcVersions", a);
        }
        if (e.group() != null) {
            j.addProperty("group", e.group());
        }
        if (e.defaultEnabled() != e.policy().defaultSelected()) {
            j.addProperty("defaultEnabled", e.defaultEnabled());
        }
        return j;
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }
}

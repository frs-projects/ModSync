package net.frsprojects.modsync.core.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves files through Modrinth's bulk hash lookup.
 *
 * <p>Modrinth indexes versions by file hash, so one request answers a whole folder. That is
 * why {@code Hashing} computes SHA-512 in the same pass as everything else: the integrity
 * hash and the lookup key are the same string.
 */
public final class ModrinthLookup implements ModMetadataLookup {

    public static final String DEFAULT_BASE_URL = "https://api.modrinth.com/v2";

    /** Modrinth accepts far more per call, but a smaller batch fails smaller. */
    private static final int BATCH = 100;

    private final JsonHttp http;
    private final String baseUrl;

    public ModrinthLookup(JsonHttp http) {
        this(http, DEFAULT_BASE_URL);
    }

    public ModrinthLookup(JsonHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String name() {
        return "Modrinth";
    }

    @Override
    public Map<String, Resolved> resolve(List<ExportCandidate> candidates) throws IOException {
        // Two files in one folder can be byte-identical, so a hash maps to a list of paths.
        Map<String, List<String>> pathsByHash = new LinkedHashMap<>();
        for (ExportCandidate c : candidates) {
            if (c.hashes().sha512() != null) {
                pathsByHash.computeIfAbsent(c.hashes().sha512(), k -> new ArrayList<>()).add(c.path());
            }
        }

        Map<String, Resolved> out = new HashMap<>();
        List<String> hashes = new ArrayList<>(pathsByHash.keySet());
        for (int from = 0; from < hashes.size(); from += BATCH) {
            List<String> batch = hashes.subList(from, Math.min(from + BATCH, hashes.size()));

            JsonArray arr = new JsonArray();
            batch.forEach(arr::add);
            JsonObject body = new JsonObject();
            body.add("hashes", arr);
            body.addProperty("algorithm", "sha512");

            JsonElement res = http.post(baseUrl + "/version_files", body, Map.of());
            if (!res.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> e : res.getAsJsonObject().entrySet()) {
                List<String> paths = pathsByHash.get(e.getKey());
                if (paths == null || !e.getValue().isJsonObject()) {
                    continue;
                }
                Resolved resolved = toResolved(e.getValue().getAsJsonObject(), e.getKey());
                if (resolved != null) {
                    paths.forEach(p -> out.put(p, resolved));
                }
            }
        }
        return out;
    }

    private static Resolved toResolved(JsonObject version, String wantedSha512) {
        String id = string(version, "project_id");
        JsonArray files = version.has("files") && version.get("files").isJsonArray()
            ? version.getAsJsonArray("files")
            : new JsonArray();

        // A version can carry sources and javadoc jars alongside the mod, so prefer the file
        // whose hash we actually asked about and only then fall back to the primary one.
        String primary = null;
        String first = null;
        for (JsonElement el : files) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject f = el.getAsJsonObject();
            String url = string(f, "url");
            if (url == null) {
                continue;
            }
            if (first == null) {
                first = url;
            }
            if (f.has("hashes") && f.get("hashes").isJsonObject()
                    && wantedSha512.equalsIgnoreCase(string(f.getAsJsonObject("hashes"), "sha512"))) {
                return new Resolved(id == null ? null : "modrinth:" + id, url);
            }
            if (primary == null && f.has("primary") && !f.get("primary").isJsonNull()
                    && f.get("primary").getAsBoolean()) {
                primary = url;
            }
        }
        String url = primary != null ? primary : first;
        if (id == null && url == null) {
            return null;
        }
        return new Resolved(id == null ? null : "modrinth:" + id, url);
    }

    private static String string(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) {
            return null;
        }
        return o.get(key).getAsString();
    }
}

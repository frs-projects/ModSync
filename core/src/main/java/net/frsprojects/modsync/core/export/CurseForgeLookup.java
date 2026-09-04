package net.frsprojects.modsync.core.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.frsprojects.modsync.core.hash.Murmur2;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves files through CurseForge's fingerprint endpoint.
 *
 * <p>CurseForge does not index by any standard digest — it uses a MurmurHash2 of the file with
 * whitespace stripped, which is what {@link Murmur2} exists for. The fingerprint is computed
 * here rather than during scanning because it reads the whole file into memory and is only
 * ever needed when a key is configured and lookups were asked for.
 *
 * <p>{@code downloadUrl} is null whenever a project has opted out of third-party downloads,
 * which is common. That is reported as unresolved rather than treated as a failure: the admin
 * still gets the entry, just without a URL.
 */
public final class CurseForgeLookup implements ModMetadataLookup {

    public static final String DEFAULT_BASE_URL = "https://api.curseforge.com/v1";

    private static final int BATCH = 100;

    private final JsonHttp http;
    private final String apiKey;
    private final Path gameDir;
    private final String baseUrl;

    public CurseForgeLookup(JsonHttp http, String apiKey, Path gameDir) {
        this(http, apiKey, gameDir, DEFAULT_BASE_URL);
    }

    public CurseForgeLookup(JsonHttp http, String apiKey, Path gameDir, String baseUrl) {
        this.http = http;
        this.apiKey = apiKey;
        this.gameDir = gameDir;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String name() {
        return "CurseForge";
    }

    @Override
    public Map<String, Resolved> resolve(List<ExportCandidate> candidates) throws IOException {
        Map<Long, List<String>> pathsByPrint = new LinkedHashMap<>();
        for (ExportCandidate c : candidates) {
            long print = Murmur2.fingerprint(gameDir.resolve(c.path()));
            pathsByPrint.computeIfAbsent(print, k -> new ArrayList<>()).add(c.path());
        }

        Map<String, Resolved> out = new HashMap<>();
        List<Long> prints = new ArrayList<>(pathsByPrint.keySet());
        for (int from = 0; from < prints.size(); from += BATCH) {
            List<Long> batch = prints.subList(from, Math.min(from + BATCH, prints.size()));

            JsonArray arr = new JsonArray();
            batch.forEach(arr::add);
            JsonObject body = new JsonObject();
            body.add("fingerprints", arr);

            JsonElement res = http.post(baseUrl + "/fingerprints", body, Map.of("x-api-key", apiKey));
            for (JsonObject match : exactMatches(res)) {
                if (!match.has("file") || !match.get("file").isJsonObject()) {
                    continue;
                }
                JsonObject file = match.getAsJsonObject("file");
                Long print = longOrNull(file, "fileFingerprint");
                List<String> paths = print == null ? null : pathsByPrint.get(print);
                if (paths == null) {
                    continue;
                }
                Long modId = longOrNull(file, "modId");
                Resolved resolved = new Resolved(
                    modId == null ? null : "curseforge:" + modId,
                    string(file, "downloadUrl"));
                paths.forEach(p -> out.put(p, resolved));
            }
        }
        return out;
    }

    private static List<JsonObject> exactMatches(JsonElement res) {
        List<JsonObject> out = new ArrayList<>();
        if (!res.isJsonObject()) {
            return out;
        }
        JsonObject o = res.getAsJsonObject();
        if (!o.has("data") || !o.get("data").isJsonObject()) {
            return out;
        }
        JsonObject data = o.getAsJsonObject("data");
        if (!data.has("exactMatches") || !data.get("exactMatches").isJsonArray()) {
            return out;
        }
        for (JsonElement el : data.getAsJsonArray("exactMatches")) {
            if (el.isJsonObject()) {
                out.add(el.getAsJsonObject());
            }
        }
        return out;
    }

    private static String string(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) {
            return null;
        }
        return o.get(key).getAsString();
    }

    private static Long longOrNull(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return o.get(key).getAsLong();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

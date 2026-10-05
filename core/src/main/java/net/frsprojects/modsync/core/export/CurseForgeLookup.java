package net.frsprojects.modsync.core.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.frsprojects.modsync.core.hash.Murmur2;
import net.frsprojects.modsync.core.manifest.ManualDownload;

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
 * which is common. Such a file gets a {@link ManualDownload} instead: the project's own
 * download page for that exact file, which the client opens in the player's browser. Building
 * that link needs the project's page URL, which the fingerprint response does not carry, so
 * those projects cost one more batched request. If that request fails the file is still
 * exported, just with neither a URL nor a page.
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
        // Files CurseForge will not hand out, waiting for their project's page URL.
        Map<Long, List<Pending>> withheld = new LinkedHashMap<>();
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
                String id = modId == null ? null : "curseforge:" + modId;
                String url = string(file, "downloadUrl");
                Resolved resolved = new Resolved(id, url);
                paths.forEach(p -> out.put(p, resolved));

                Long fileId = longOrNull(file, "id");
                if (url == null && modId != null && fileId != null) {
                    withheld.computeIfAbsent(modId, k -> new ArrayList<>())
                        .add(new Pending(id, fileId, string(file, "fileName"), paths));
                }
            }
        }

        if (!withheld.isEmpty()) {
            Map<Long, String> pages;
            try {
                pages = projectPages(new ArrayList<>(withheld.keySet()));
            } catch (IOException e) {
                // The fingerprint answers are still worth keeping; these files just export
                // without a page, exactly as they did before manual downloads existed.
                return out;
            }
            withheld.forEach((modId, files) -> {
                String page = pages.get(modId);
                if (page == null) {
                    return;
                }
                for (Pending f : files) {
                    Resolved resolved = new Resolved(f.id(), null,
                        new ManualDownload(page + "/download/" + f.fileId(), f.fileName()));
                    f.paths().forEach(p -> out.put(p, resolved));
                }
            });
        }
        return out;
    }

    private record Pending(String id, long fileId, String fileName, List<String> paths) {}

    /**
     * Each project's page, e.g. {@code https://www.curseforge.com/minecraft/mc-mods/jei}, by
     * mod id. Only HTTPS pages are kept: the client refuses to open anything else.
     */
    private Map<Long, String> projectPages(List<Long> modIds) throws IOException {
        Map<Long, String> pages = new HashMap<>();
        for (int from = 0; from < modIds.size(); from += BATCH) {
            JsonArray arr = new JsonArray();
            modIds.subList(from, Math.min(from + BATCH, modIds.size())).forEach(arr::add);
            JsonObject body = new JsonObject();
            body.add("modIds", arr);

            JsonElement res = http.post(baseUrl + "/mods", body, Map.of("x-api-key", apiKey));
            if (!res.isJsonObject() || !res.getAsJsonObject().has("data")
                    || !res.getAsJsonObject().get("data").isJsonArray()) {
                continue;
            }
            for (JsonElement el : res.getAsJsonObject().getAsJsonArray("data")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject mod = el.getAsJsonObject();
                Long id = longOrNull(mod, "id");
                if (id == null || !mod.has("links") || !mod.get("links").isJsonObject()) {
                    continue;
                }
                String page = string(mod.getAsJsonObject("links"), "websiteUrl");
                if (page == null || !page.startsWith("https://")) {
                    continue;
                }
                pages.put(id, page.endsWith("/") ? page.substring(0, page.length() - 1) : page);
            }
        }
        return pages;
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

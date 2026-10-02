package net.frsprojects.modsync.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.frsprojects.modsync.core.profile.ModSyncPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which manifest each server syncs against: {@code config/modsync-servers.json}.
 *
 * <p>Kept apart from {@link ModSyncConfig} on purpose. This file is meant to ship inside a
 * modpack, so players get the right manifest without editing anything; the client config holds
 * a personal API key and must never ship. One file for both would make every pack author
 * choose between leaking the key and not shipping the URLs.
 */
public record ServerManifests(
    int formatVersion,
    /** Manifest URL per server, keyed by {@code host:port} (or just {@code host} for 25565). */
    Map<String, String> servers
) {

    public static final int CURRENT_FORMAT_VERSION = 1;

    /** Minecraft's port, implied when a server address has none. */
    public static final int DEFAULT_PORT = 25565;

    private static final String COMMENT =
        "Manifest URL per server, keyed by the address as typed in the server list "
            + "(\"play.example.net\" or \"play.example.net:25566\"). Joining a listed server "
            + "syncs its mods first. Safe to ship in a modpack.";

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    public static ServerManifests empty() {
        return new ServerManifests(CURRENT_FORMAT_VERSION, Map.of());
    }

    /**
     * Loads {@link ModSyncPaths#serverManifests()}, creating it if it does not exist. A new file
     * takes over any {@code manifestOverrides} that 0.1.2 kept in the client config, and those
     * are then dropped from the client config so there is one place to edit them.
     */
    public static ServerManifests loadOrCreate(ModSyncPaths paths) throws IOException {
        Path file = paths.serverManifests();
        if (Files.isRegularFile(file)) {
            return load(file);
        }
        Map<String, String> migrated = legacyOverrides(paths.config());
        ServerManifests created = new ServerManifests(CURRENT_FORMAT_VERSION, migrated);
        created.save(file);
        if (!migrated.isEmpty()) {
            // Rewriting drops the key: ModSyncConfig no longer knows it.
            ModSyncConfig.load(paths.config()).save(paths.config());
        }
        return created;
    }

    /** Reads the file; a missing file means no servers. */
    public static ServerManifests load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return empty();
        }
        JsonObject o = parse(file);
        return new ServerManifests(
            o.has("formatVersion") ? o.get("formatVersion").getAsInt() : CURRENT_FORMAT_VERSION,
            urlMap(o.get("servers"), file, "servers"));
    }

    public void save(Path file) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("_comment", COMMENT);
        o.addProperty("formatVersion", formatVersion);
        JsonObject map = new JsonObject();
        servers.forEach(map::addProperty);
        o.add("servers", map);

        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The manifest URL configured for a server, or null when there is none.
     *
     * <p>Hosts compare case-insensitively, since that is how a player types them. A key with no
     * port matches the default port only, because that is what the same text in the server
     * list's address box means.
     */
    public String urlFor(String host, int port) {
        String wanted = host.toLowerCase(Locale.ROOT) + ":" + port;
        for (var e : servers.entrySet()) {
            String key = e.getKey().trim().toLowerCase(Locale.ROOT);
            if (key.indexOf(':') < 0) {
                key = key + ":" + DEFAULT_PORT;
            }
            if (key.equals(wanted) && !e.getValue().isBlank()) {
                return e.getValue().trim();
            }
        }
        return null;
    }

    private static Map<String, String> legacyOverrides(Path clientConfig) throws IOException {
        if (!Files.isRegularFile(clientConfig)) {
            return Map.of();
        }
        return urlMap(parse(clientConfig).get("manifestOverrides"), clientConfig,
            "manifestOverrides");
    }

    private static JsonObject parse(Path file) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException(file + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Reads an object of {@code "host:port": "url"} pairs. An array of such objects is accepted
     * too, because that is an easy mistake to make and it means the same thing. Anything else
     * fails loudly: ignoring it would look exactly like a server that has no manifest.
     */
    private static Map<String, String> urlMap(JsonElement el, Path file, String key)
            throws IOException {
        if (el == null || el.isJsonNull()) {
            return Map.of();
        }
        List<JsonObject> objects = new ArrayList<>();
        if (el.isJsonObject()) {
            objects.add(el.getAsJsonObject());
        } else if (el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    throw malformed(file, key);
                }
                objects.add(item.getAsJsonObject());
            }
        } else {
            throw malformed(file, key);
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (JsonObject obj : objects) {
            for (var e : obj.entrySet()) {
                if (!e.getValue().isJsonPrimitive()) {
                    throw malformed(file, key);
                }
                out.put(e.getKey(), e.getValue().getAsString());
            }
        }
        // Unmodifiable but ordered, so a rewritten file keeps the player's order.
        return Collections.unmodifiableMap(out);
    }

    private static IOException malformed(Path file, String key) {
        return new IOException(key + " in " + file + " must look like "
            + "{\"play.example.net\": \"https://example.net/manifest.json\"}");
    }
}

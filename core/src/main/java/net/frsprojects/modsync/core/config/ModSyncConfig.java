package net.frsprojects.modsync.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side settings.
 *
 * <p>{@link #alwaysKeep} is the important one: without it, the first sync to any server
 * sweeps away the player's own client-side mods, which no server can know about.
 */
public record ModSyncConfig(
    int formatVersion,
    /** Globs, game-directory-relative, that ModSync must never quarantine or replace. */
    List<String> alwaysKeep,
    /** Extra hosts the player has approved for downloads, beyond the built-in allowlist. */
    List<String> approvedHosts,
    /** Concurrent downloads. */
    int parallelDownloads,
    /** Per-server manifest URL overrides, keyed by {@code host:port}. */
    Map<String, String> manifestOverrides,
    /** Probe the server's HTTP endpoint automatically when joining. */
    boolean autoProbe,
    /**
     * Personal CurseForge API key, used only by {@code /modsync export} to turn local files
     * into download URLs. Empty means CurseForge is not queried. This is a secret: it belongs
     * to the person who requested it, not to the pack.
     */
    String curseForgeApiKey
) {

    public static final int CURRENT_FORMAT_VERSION = 1;

    /**
     * Written into every config so the warning travels with the file. A modpack is usually
     * published by zipping a working game directory, which is exactly how a private API key
     * ends up on the internet.
     */
    private static final String WARNING =
        "This file may contain your personal CurseForge API key. NEVER ship modsync/modsync.json "
            + "inside a published modpack, and never paste it into an issue or a chat.";

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    public static ModSyncConfig defaults() {
        return new ModSyncConfig(
            CURRENT_FORMAT_VERSION,
            // Seeded with the usual client-only suspects so a first-time player does not
            // lose their setup before they have ever opened the settings.
            List.of("mods/iris-*.jar", "mods/sodium-extra-*.jar", "shaderpacks/**"),
            List.of(),
            4,
            Map.of(),
            true,
            "");
    }

    public static ModSyncConfig load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return defaults();
        }
        JsonObject o;
        try {
            o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("ModSync config " + file + " is not valid JSON: "
                + e.getMessage(), e);
        }

        ModSyncConfig d = defaults();
        return new ModSyncConfig(
            o.has("formatVersion") ? o.get("formatVersion").getAsInt() : CURRENT_FORMAT_VERSION,
            stringList(o, "alwaysKeep", d.alwaysKeep()),
            stringList(o, "approvedHosts", d.approvedHosts()),
            // Clamped: a manifest cannot set this, but a hand-edited config should not be
            // able to open a thousand sockets either.
            Math.max(1, Math.min(16,
                o.has("parallelDownloads") ? o.get("parallelDownloads").getAsInt()
                    : d.parallelDownloads())),
            stringMap(o, "manifestOverrides"),
            o.has("autoProbe") ? o.get("autoProbe").getAsBoolean() : d.autoProbe(),
            optString(o, "curseForgeApiKey", d.curseForgeApiKey()));
    }

    public void save(Path file) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("_warning", WARNING);
        o.addProperty("formatVersion", formatVersion);

        JsonArray keep = new JsonArray();
        alwaysKeep.forEach(keep::add);
        o.add("alwaysKeep", keep);

        JsonArray hosts = new JsonArray();
        approvedHosts.forEach(hosts::add);
        o.add("approvedHosts", hosts);

        o.addProperty("parallelDownloads", parallelDownloads);

        JsonObject overrides = new JsonObject();
        manifestOverrides.forEach(overrides::addProperty);
        o.add("manifestOverrides", overrides);

        o.addProperty("autoProbe", autoProbe);
        o.addProperty("curseForgeApiKey", curseForgeApiKey);

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

    /** The manifest URL to use for a server, or null to probe the default endpoint. */
    public String manifestOverrideFor(String hostPort) {
        return manifestOverrides.get(hostPort);
    }

    private static String optString(JsonObject o, String key, String fallback) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return o.get(key).getAsString().trim();
    }

    private static List<String> stringList(JsonObject o, String key, List<String> fallback) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return fallback;
        }
        List<String> out = new ArrayList<>();
        for (var el : o.getAsJsonArray(key)) {
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, String> stringMap(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : o.getAsJsonObject(key).entrySet()) {
            if (e.getValue().isJsonPrimitive()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return Map.copyOf(out);
    }
}

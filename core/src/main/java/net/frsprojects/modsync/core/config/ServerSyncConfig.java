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
import java.util.List;

/**
 * Dedicated-server settings: {@code config/modsync-server.json}.
 *
 * <p>Separate from {@link ModSyncConfig} because a server has no player and no API key, and
 * its defaults differ: nothing is kept by default (a server has no shaderpacks to protect),
 * and nothing syncs until {@link #manifestUrl} is set.
 */
public record ServerSyncConfig(
    int formatVersion,
    /** The manifest this server keeps itself in line with. Blank means the server never syncs. */
    String manifestUrl,
    /** Globs, game-directory-relative, that ModSync must never quarantine or replace. */
    List<String> alwaysKeep,
    /** Extra hosts approved for downloads, beyond the built-in allowlist. */
    List<String> approvedHosts,
    /** Concurrent downloads. */
    int parallelDownloads,
    /**
     * Stop the server once an update found at startup is downloaded, so it is applied straight
     * away. Only useful when something restarts the server after it stops (a panel, a systemd
     * unit, a start script with a loop); otherwise the update waits for the next manual start.
     */
    boolean restartAfterUpdate
) {

    public static final int CURRENT_FORMAT_VERSION = 1;

    private static final String COMMENT =
        "Set manifestUrl to keep this server's mods in line with that manifest. It is checked "
            + "on startup and with /modsync update; changes are applied when the server stops. "
            + "Files the manifest does not list are moved to modsync/quarantine unless they "
            + "match alwaysKeep.";

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    public static ServerSyncConfig defaults() {
        return new ServerSyncConfig(CURRENT_FORMAT_VERSION, "", List.of(), List.of(), 4, false);
    }

    public boolean isConfigured() {
        return manifestUrl != null && !manifestUrl.isBlank();
    }

    /** The settings {@code SyncSession} reads, in the shape it reads them. */
    public ModSyncConfig toSyncConfig() {
        return new ModSyncConfig(ModSyncConfig.CURRENT_FORMAT_VERSION, alwaysKeep, approvedHosts,
            parallelDownloads, false, "", List.of());
    }

    public static ServerSyncConfig load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return defaults();
        }
        JsonObject o;
        try {
            o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("ModSync server config " + file + " is not valid JSON: "
                + e.getMessage(), e);
        }

        ServerSyncConfig d = defaults();
        return new ServerSyncConfig(
            o.has("formatVersion") ? o.get("formatVersion").getAsInt() : CURRENT_FORMAT_VERSION,
            optString(o, "manifestUrl", d.manifestUrl()),
            stringList(o, "alwaysKeep", d.alwaysKeep()),
            stringList(o, "approvedHosts", d.approvedHosts()),
            Math.max(1, Math.min(16,
                o.has("parallelDownloads") ? o.get("parallelDownloads").getAsInt()
                    : d.parallelDownloads())),
            o.has("restartAfterUpdate") ? o.get("restartAfterUpdate").getAsBoolean()
                : d.restartAfterUpdate());
    }

    /** Like {@link #load}, but writes the defaults first so an admin has a file to edit. */
    public static ServerSyncConfig loadOrCreate(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            ServerSyncConfig d = defaults();
            d.save(file);
            return d;
        }
        return load(file);
    }

    public void save(Path file) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("_comment", COMMENT);
        o.addProperty("formatVersion", formatVersion);
        o.addProperty("manifestUrl", manifestUrl);

        JsonArray keep = new JsonArray();
        alwaysKeep.forEach(keep::add);
        o.add("alwaysKeep", keep);

        JsonArray hosts = new JsonArray();
        approvedHosts.forEach(hosts::add);
        o.add("approvedHosts", hosts);

        o.addProperty("parallelDownloads", parallelDownloads);
        o.addProperty("restartAfterUpdate", restartAfterUpdate);

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
}

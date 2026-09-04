package net.frsprojects.modsync.core.profile;

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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and writes {@link Profile} records.
 *
 * <p>Unlike the journal, this only ever runs inside Minecraft, so Gson — which Minecraft
 * bundles on every loader — is available.
 */
public final class ProfileStore {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private final ModSyncPaths paths;

    public ProfileStore(ModSyncPaths paths) {
        this.paths = paths;
    }

    public void save(Profile profile) throws IOException {
        Path file = paths.profileFile(profile.profileId());
        Files.createDirectories(file.getParent());

        JsonObject o = new JsonObject();
        o.addProperty("formatVersion", profile.formatVersion());
        o.addProperty("profileId", profile.profileId());
        if (profile.packId() != null) {
            o.addProperty("packId", profile.packId());
        }
        o.addProperty("packName", profile.packName());
        o.addProperty("packVersion", profile.packVersion());
        o.addProperty("appliedAt", profile.appliedAt().toString());

        JsonArray files = new JsonArray();
        for (Profile.ProfileFile f : profile.files()) {
            JsonObject j = new JsonObject();
            j.addProperty("path", f.path());
            j.addProperty("sha512", f.sha512());
            j.addProperty("size", f.size());
            files.add(j);
        }
        o.add("files", files);

        writeAtomically(file, GSON.toJson(o));
    }

    public Optional<Profile> load(String profileId) throws IOException {
        Path file = paths.profileFile(profileId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        JsonObject o;
        try {
            o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Profile " + file + " is corrupt: " + e.getMessage(), e);
        }

        int formatVersion = o.has("formatVersion") ? o.get("formatVersion").getAsInt() : 1;
        if (formatVersion > Profile.CURRENT_FORMAT_VERSION) {
            throw new IOException(
                "Profile " + profileId + " was written by a newer ModSync (format "
                    + formatVersion + "); update ModSync");
        }

        List<Profile.ProfileFile> files = new ArrayList<>();
        if (o.has("files") && o.get("files").isJsonArray()) {
            for (var el : o.getAsJsonArray("files")) {
                JsonObject j = el.getAsJsonObject();
                files.add(new Profile.ProfileFile(
                    j.get("path").getAsString(),
                    j.has("sha512") ? j.get("sha512").getAsString() : null,
                    j.has("size") ? j.get("size").getAsLong() : -1L));
            }
        }

        return Optional.of(new Profile(
            formatVersion,
            o.get("profileId").getAsString(),
            o.has("packId") ? o.get("packId").getAsString() : null,
            o.has("packName") ? o.get("packName").getAsString() : profileId,
            o.has("packVersion") ? o.get("packVersion").getAsString() : "0",
            parseInstant(o),
            List.copyOf(files)));
    }

    public List<String> listProfileIds() throws IOException {
        Path root = paths.profilesRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        }
    }

    /**
     * Every hash any profile still refers to. Anything outside this set is safe for
     * {@link ContentCache#prune} to delete.
     */
    public Set<String> allReferencedHashes() throws IOException {
        Set<String> hashes = new LinkedHashSet<>();
        for (String id : listProfileIds()) {
            load(id).ifPresent(p -> hashes.addAll(p.hashes()));
        }
        return hashes;
    }

    /** Which profile {@code mods/} currently holds, if ModSync has ever applied one. */
    public Optional<String> activeProfileId() throws IOException {
        Path file = paths.activeState();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
            return o.has("profileId")
                ? Optional.of(o.get("profileId").getAsString())
                : Optional.empty();
        } catch (JsonParseException | IllegalStateException e) {
            // A corrupt marker just means "unknown"; the next sync will rewrite it.
            return Optional.empty();
        }
    }

    public void setActiveProfile(String profileId) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("profileId", profileId);
        o.addProperty("appliedAt", Instant.now().toString());
        writeAtomically(paths.activeState(), GSON.toJson(o));
    }

    private static Instant parseInstant(JsonObject o) {
        if (!o.has("appliedAt")) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(o.get("appliedAt").getAsString());
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

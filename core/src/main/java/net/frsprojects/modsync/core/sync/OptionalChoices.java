package net.frsprojects.modsync.core.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.frsprojects.modsync.core.diff.SyncAction;
import net.frsprojects.modsync.core.diff.SyncPlan;
import net.frsprojects.modsync.core.manifest.ManifestEntry;
import net.frsprojects.modsync.core.manifest.Policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the player decided about one pack's {@code recommend} and {@code optional} files.
 *
 * <p>A choice is keyed by the entry's {@link ManifestEntry#identity() identity} and pinned to
 * the SHA-512 it was made against. A pack that ships a new version of a file has changed what
 * the player is agreeing to, so the choice stops counting as settled and the file is offered
 * again — pre-ticked the way the player last left it.
 *
 * <p>A choice is only <em>settled</em> when the player asked not to be asked again. Otherwise
 * it is remembered as the starting state of the next prompt, but the prompt still appears.
 *
 * <p>A declined choice also covers a copy that is already installed: {@code /modsync optional
 * ... remove} records one, and the next sync moves the file aside.
 */
public final class OptionalChoices {

    /** One remembered decision. */
    public record Choice(String label, String sha512, boolean install, boolean quiet) {}

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private final Map<String, Choice> choices;

    private OptionalChoices(Map<String, Choice> choices) {
        this.choices = choices;
    }

    public static OptionalChoices empty() {
        return new OptionalChoices(new TreeMap<>());
    }

    /** Reads the choices file. A missing or corrupt file means nothing has been decided. */
    public static OptionalChoices load(Path file) {
        if (!Files.isRegularFile(file)) {
            return empty();
        }
        Map<String, Choice> out = new TreeMap<>();
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
            if (o.has("choices") && o.get("choices").isJsonObject()) {
                for (var e : o.getAsJsonObject("choices").entrySet()) {
                    if (!e.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject c = e.getValue().getAsJsonObject();
                    if (!c.has("sha512") || !c.has("install")) {
                        continue;
                    }
                    String label = c.has("label") ? c.get("label").getAsString() : e.getKey();
                    out.put(e.getKey(), new Choice(label, c.get("sha512").getAsString(),
                        c.get("install").getAsBoolean(),
                        c.has("quiet") && c.get("quiet").getAsBoolean()));
                }
            }
        } catch (IOException | JsonParseException | IllegalStateException
                | UnsupportedOperationException e) {
            // Losing these only means the player is asked again; not worth failing a join over.
            return empty();
        }
        return new OptionalChoices(out);
    }

    public void save(Path file) throws IOException {
        JsonObject root = new JsonObject();
        JsonObject all = new JsonObject();
        for (var e : choices.entrySet()) {
            JsonObject c = new JsonObject();
            c.addProperty("label", e.getValue().label());
            c.addProperty("sha512", e.getValue().sha512());
            c.addProperty("install", e.getValue().install());
            c.addProperty("quiet", e.getValue().quiet());
            all.add(e.getKey(), c);
        }
        root.add("choices", all);

        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Records a decision about the version of {@code entry} the player was just shown. */
    public void record(ManifestEntry entry, boolean install, boolean quiet) {
        choices.put(entry.identity(),
            new Choice(entry.label(), entry.hashes().sha512(), install, quiet));
    }

    /** Every remembered choice, by identity. */
    public Map<String, Choice> all() {
        return Collections.unmodifiableMap(choices);
    }

    /**
     * Declines a file the player took earlier, without asking again. The next sync moves an
     * installed copy aside.
     *
     * @return false when there is no choice for this identity
     */
    public boolean decline(String identity) {
        Choice c = choices.get(identity);
        if (c == null) {
            return false;
        }
        choices.put(identity, new Choice(c.label(), c.sha512(), false, true));
        return true;
    }

    /** Forgets a choice, so the file is offered again if it is missing. */
    public boolean forget(String identity) {
        return choices.remove(identity) != null;
    }

    public void clear() {
        choices.clear();
    }

    /** Identities of the files the player does not want, for {@link
     *  net.frsprojects.modsync.core.diff.Differ}. */
    public Set<String> declined() {
        Set<String> out = new TreeSet<>();
        for (var e : choices.entrySet()) {
            if (!e.getValue().install()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * The optional actions with this policy that the player still has to be asked about:
     * every pending change that has no settled choice for exactly this version.
     */
    public List<SyncAction> undecided(SyncPlan plan, Policy policy) {
        List<SyncAction> out = new ArrayList<>();
        for (SyncAction a : plan.changes()) {
            if (a.isOffer() && a.entry().policy() == policy && !isSettled(a.entry())) {
                out.add(a);
            }
        }
        return out;
    }

    /** Whether the prompt should start with this action ticked. */
    public boolean initiallyChecked(SyncAction action) {
        Choice c = action.entry() == null ? null : choices.get(action.entry().identity());
        // An earlier answer, even about an older version, beats the pack's default: a player
        // who took an optional mod wants its update, and one who declined it still does not.
        return c != null ? c.install() : action.selectedByDefault();
    }

    /**
     * The selection to start from: everything mandatory, plus each optional change ticked the
     * way {@link #initiallyChecked} says. Settled choices are final here; the rest are only a
     * starting point for the prompt.
     */
    public Set<String> initialSelection(SyncPlan plan) {
        Set<String> selected = new LinkedHashSet<>();
        for (SyncAction a : plan.changes()) {
            if (a.isOffer() ? initiallyChecked(a) : a.selectedByDefault()) {
                selected.add(a.path());
            }
        }
        return selected;
    }

    private boolean isSettled(ManifestEntry entry) {
        Choice c = choices.get(entry.identity());
        return c != null && c.quiet() && c.sha512().equals(entry.hashes().sha512());
    }
}

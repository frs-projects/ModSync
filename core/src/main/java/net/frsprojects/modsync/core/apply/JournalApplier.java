package net.frsprojects.modsync.core.apply;

import net.frsprojects.modsync.core.hash.Hashing;
import net.frsprojects.modsync.core.profile.ContentCache;
import net.frsprojects.modsync.core.profile.ModSyncPaths;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Executes a {@link Journal}.
 *
 * <p>Why this exists at all: a running Minecraft holds every jar in {@code mods/} open, and
 * on Windows an open file cannot be deleted, renamed or overwritten. Fabric's
 * {@code preLaunch} entrypoint is no help either, because mod discovery has already opened
 * the jars by the time it runs. So the swap has to happen when no game process is alive —
 * either from a detached helper that outlives the game, or at the very start of the next
 * launch.
 *
 * <p>Every operation is idempotent, so recovery from a crash mid-apply is simply replaying
 * the whole journal from the top. Nothing here deletes user content: displaced files are
 * moved to quarantine by the plan that produced the journal.
 */
public final class JournalApplier {

    private final ModSyncPaths paths;
    private final ContentCache cache;

    public JournalApplier(ModSyncPaths paths) {
        this.paths = paths;
        this.cache = new ContentCache(paths);
    }

    /** Applies the journal at the standard location, then removes it. */
    public Result applyPending() throws IOException {
        Path journalFile = paths.journal();
        Journal journal = Journal.readFrom(journalFile);
        if (journal.isEmpty()) {
            Files.deleteIfExists(journalFile);
            return new Result(0, 0);
        }
        Result result = apply(journal);
        Files.deleteIfExists(journalFile);
        return result;
    }

    public Result apply(Journal journal) throws IOException {
        int applied = 0;
        int alreadyDone = 0;
        for (JournalOp op : journal.ops()) {
            if (applyOne(op)) {
                applied++;
            } else {
                alreadyDone++;
            }
        }
        return new Result(applied, alreadyDone);
    }

    /** @return true if this call changed the filesystem, false if it was already satisfied */
    private boolean applyOne(JournalOp op) throws IOException {
        return switch (op.kind()) {
            case MKDIR -> {
                Path dir = resolve(op.a());
                if (Files.isDirectory(dir)) {
                    yield false;
                }
                Files.createDirectories(dir);
                yield true;
            }
            case MOVE -> move(resolve(op.a()), resolve(op.b()));
            case LINK -> link(op.a(), resolve(op.b()));
        };
    }

    private boolean move(Path from, Path to) throws IOException {
        if (!Files.exists(from)) {
            // A replay after the move already succeeded. Only benign if the destination
            // is there; otherwise the file has gone somewhere we did not put it.
            if (Files.exists(to)) {
                return false;
            }
            throw new JournalException(
                "Cannot move " + from + ": it no longer exists and " + to + " was never created");
        }
        Files.createDirectories(to.getParent());
        Path target = to;
        if (Files.exists(target)) {
            // Quarantine must never clobber an earlier quarantined file of the same name,
            // or the user silently loses the older one.
            target = uniqueSibling(to);
        }
        try {
            Files.move(from, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, target);
        }
        return true;
    }

    private boolean link(String sha512, Path destination) throws IOException {
        if (Files.isRegularFile(destination)
            && Hashing.sha512(destination).equals(sha512.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (!cache.contains(sha512)) {
            throw new JournalException(
                "Cannot place " + destination + ": " + sha512 + " is missing from the cache");
        }
        cache.materialise(sha512, destination);
        return true;
    }

    private Path resolve(String relative) {
        return paths.gameDir().resolve(relative).normalize();
    }

    private static Path uniqueSibling(Path desired) {
        String name = desired.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot <= 0 ? name : name.substring(0, dot);
        String ext = dot <= 0 ? "" : name.substring(dot);
        for (int i = 1; i < 1000; i++) {
            Path candidate = desired.resolveSibling(stem + "." + i + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot find a free name next to " + desired);
    }

    /** Outcome of an apply run. */
    public record Result(int applied, int alreadySatisfied) {
        public int total() {
            return applied + alreadySatisfied;
        }
    }

    // ── Standalone entry point ──────────────────────────────────────────────────

    /**
     * Runs the applier as its own process.
     *
     * <p>Invoked as {@code java -cp modsync.jar
     * net.frsprojects.modsync.core.apply.JournalApplier <gameDir> [--wait-for-pid <pid>]}.
     * With {@code --wait-for-pid} it blocks until that process exits, which is how the swap
     * happens after Minecraft has released its file handles.
     *
     * <p>This class and everything it touches must stay free of Gson: Minecraft supplies
     * Gson at runtime, and this JVM has no Minecraft.
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println(
                "usage: JournalApplier <gameDir> [--wait-for-pid <pid>] [--timeout-seconds <n>]");
            System.exit(2);
            return;
        }
        Path gameDir = Path.of(args[0]);
        long waitPid = -1;
        long timeoutSeconds = 120;
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals("--wait-for-pid")) {
                waitPid = Long.parseLong(args[++i]);
            } else if (args[i].equals("--timeout-seconds")) {
                timeoutSeconds = Long.parseLong(args[++i]);
            }
        }

        if (waitPid > 0 && !awaitExit(waitPid, Duration.ofSeconds(timeoutSeconds))) {
            System.err.println(
                "[ModSync] process " + waitPid + " is still running after " + timeoutSeconds
                    + "s; leaving the journal in place for the next launch");
            System.exit(1);
            return;
        }

        try {
            Result result = new JournalApplier(new ModSyncPaths(gameDir)).applyPending();
            System.out.println("[ModSync] applied " + result.applied() + " operation(s), "
                + result.alreadySatisfied() + " already satisfied");
        } catch (IOException e) {
            // The journal is left in place deliberately: it is idempotent, so the next
            // launch can retry rather than leaving mods/ half-swapped.
            System.err.println("[ModSync] failed to apply pending operations: " + e.getMessage());
            System.exit(1);
        }
    }

    /** @return true if the process exited (or was never running) within the timeout */
    private static boolean awaitExit(long pid, Duration timeout) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return true;
        }
        try {
            handle.get().onExit().get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            // onExit() failing means we cannot observe the process; assume it is gone
            // rather than blocking the swap forever.
            return true;
        }
    }
}

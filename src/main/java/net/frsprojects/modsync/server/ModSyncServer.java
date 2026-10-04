package net.frsprojects.modsync.server;

import net.frsprojects.modsync.ModSync;
import net.frsprojects.modsync.command.ModSyncCommand;
import net.frsprojects.modsync.core.apply.ApplierLauncher;
import net.frsprojects.modsync.core.apply.JournalApplier;
import net.frsprojects.modsync.core.config.ServerSyncConfig;
import net.frsprojects.modsync.core.profile.ModSyncPaths;
import net.frsprojects.modsync.core.sync.ServerUpdater;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Dedicated-server half of ModSync: keeps the server's own mods in line with the manifest in
 * {@code config/modsync-server.json}.
 *
 * <p>The check runs on a background thread as the server starts, so a slow manifest host never holds up
 * startup, and again on {@code /modsync update}. Anything it stages is applied once the server
 * has stopped: the running server already has its mods loaded, and swapping them under it would
 * change nothing until the next start anyway.
 *
 * <p>Loader-neutral. Each loader's entrypoint calls {@link #init} on a dedicated server only,
 * and forwards its server-started and server-stopped events.
 */
public final class ModSyncServer {

    private static final Logger LOG = LoggerFactory.getLogger("ModSync");

    /** How long the shutdown hook waits for the server thread to finish saving. */
    private static final long STOP_WAIT_MILLIS = 60_000;

    private static final AtomicBoolean CHECKING = new AtomicBoolean();

    private static ModSyncPaths paths;
    private static ServerUpdater updater;
    private static Supplier<List<Path>> modClasspath;
    private static volatile MinecraftServer server;
    /** Set when the startup check staged an update and the admin wants it applied at once. */
    private static boolean restartWanted;

    private ModSyncServer() {}

    /**
     * @param loader the loader id as manifests spell it, e.g. {@code neoforge}
     * @param mcVersion the running Minecraft version, from the loader
     * @param modClasspath ModSync's own jar, for the detached applier fallback
     */
    public static void init(Path gameDir, String loader, String mcVersion,
            Supplier<List<Path>> modClasspath) {
        ModSyncServer.paths = new ModSyncPaths(gameDir);
        ModSyncServer.updater = new ServerUpdater(paths, loader, mcVersion,
            "ModSync/" + ModSync.VERSION);
        ModSyncServer.modClasspath = modClasspath;

        Runtime.getRuntime().addShutdownHook(
            new Thread(ModSyncServer::applyOnExit, "ModSync-Server-Apply"));

        if (updater.hasPending()) {
            LOG.warn("An update staged before the last stop was never applied; it will be "
                + "re-checked now and applied when this server stops");
        }
        startCheck(true, null);
    }

    public static void onServerStarted(MinecraftServer started) {
        server = started;
        restartIfWanted();
    }

    /** The server thread has finished saving: the safe moment to swap files. */
    public static void onServerStopped(MinecraftServer stopped) {
        server = null;
        applyInProcess();
    }

    /**
     * Runs a check for {@code /modsync update}.
     *
     * @return false when a check is already running
     */
    public static boolean checkNow(ModSyncCommand.Channel channel) {
        if (updater == null) {
            channel.error("ModSync server sync is not initialised.");
            return false;
        }
        return startCheck(false, channel);
    }

    private static boolean startCheck(boolean startup, ModSyncCommand.Channel channel) {
        if (!CHECKING.compareAndSet(false, true)) {
            if (channel != null) {
                channel.error("A ModSync update check is already running.");
            }
            return false;
        }
        Thread worker = new Thread(() -> {
            try {
                report(updater.check(line -> {
                    LOG.info(line);
                    post(channel, () -> channel.info(line));
                }), startup, channel);
            } catch (Throwable t) {
                LOG.error("ModSync update check failed", t);
                post(channel, () -> channel.error("Update check failed: " + t));
            } finally {
                CHECKING.set(false);
            }
        }, "ModSync-Server-Check");
        // Daemon: a hung download must never keep a stopped server's JVM alive.
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private static void report(ServerUpdater.Result result, boolean startup,
            ModSyncCommand.Channel channel) {
        switch (result.outcome()) {
            case NOT_CONFIGURED -> {
                LOG.info(result.summary());
                post(channel, () -> channel.warn(result.summary()));
            }
            case UP_TO_DATE -> {
                LOG.info(result.summary());
                post(channel, () -> channel.info(result.summary()));
            }
            case STAGED -> {
                LOG.info(result.summary());
                result.details().forEach(d -> LOG.info("  {}", d));
                post(channel, () -> {
                    channel.info(result.summary());
                    result.details().forEach(channel::info);
                    channel.warn("Restart the server to apply the update.");
                });
                if (startup && restartAfterUpdate()) {
                    synchronized (ModSyncServer.class) {
                        restartWanted = true;
                    }
                    restartIfWanted();
                }
            }
            case BLOCKED, FAILED -> {
                LOG.error(result.summary());
                result.details().forEach(d -> LOG.error("  {}", d));
                post(channel, () -> {
                    channel.error(result.summary());
                    result.details().forEach(channel::error);
                });
            }
        }
    }

    /**
     * Stops the server so a startup update is applied straight away, once both the update is
     * staged and the server is up; whichever happens second triggers it. Never with players
     * online: a slow download can finish after someone has joined, and kicking them for an
     * update that can wait is worse than waiting.
     */
    private static void restartIfWanted() {
        MinecraftServer s;
        synchronized (ModSyncServer.class) {
            s = server;
            if (!restartWanted || s == null) {
                return;
            }
            restartWanted = false;
        }
        s.execute(() -> {
            if (s.getPlayerCount() > 0) {
                LOG.warn("Not restarting for the ModSync update: players are online. It will "
                    + "be applied when the server next stops.");
                return;
            }
            LOG.warn("Stopping the server to apply the ModSync update (restartAfterUpdate)");
            s.halt(false);
        });
    }

    private static boolean restartAfterUpdate() {
        try {
            return ServerSyncConfig.load(paths.serverConfig()).restartAfterUpdate();
        } catch (IOException e) {
            return false;
        }
    }

    /** @return true when nothing is left to apply */
    private static boolean applyInProcess() {
        try {
            JournalApplier.Result result = updater.applyPending();
            if (result != null) {
                // The logger may already be shut down this late; stdout still reaches the
                // console and the panel.
                System.out.println("[ModSync] applied the staged update: " + result.applied()
                    + " operation(s), " + result.alreadySatisfied() + " already satisfied");
            }
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[ModSync] could not apply the staged update in-process ("
                + e.getMessage() + "); handing it to the applier once this process exits");
            return false;
        }
    }

    /**
     * Last chance to apply, for a JVM that exits without a server-stopped event (a crash, or a
     * SIGTERM racing the server's own shutdown). If the in-process apply fails, which is what
     * Windows does to a jar the JVM still holds, the detached applier takes over once this
     * process is gone.
     */
    private static void applyOnExit() {
        if (!updater.hasPending()) {
            return;
        }
        MinecraftServer s = server;
        if (s != null) {
            // A SIGTERM runs every shutdown hook at once, including the one that stops the
            // server, so wait for the world to finish saving before moving anything.
            try {
                s.getRunningThread().join(STOP_WAIT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (applyInProcess()) {
            return;
        }
        try {
            ApplierLauncher.launch(modClasspath.get(), paths);
        } catch (IOException | RuntimeException e) {
            System.err.println("[ModSync] could not start the applier; the update will be "
                + "re-staged on the next start: " + e);
        }
    }

    private static void post(ModSyncCommand.Channel channel, Runnable action) {
        if (channel != null) {
            channel.mainThread().execute(action);
        }
    }
}

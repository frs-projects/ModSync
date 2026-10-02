package net.frsprojects.modsync.client;

import net.frsprojects.modsync.core.apply.ApplierLauncher;
import net.frsprojects.modsync.core.config.ModSyncConfig;
import net.frsprojects.modsync.core.config.ServerManifests;
import net.frsprojects.modsync.core.profile.ModSyncPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Client half of ModSync: decides whether joining a server should sync first, and arranges
 * for the applier to run once the game exits.
 *
 * <p>The sync runs <em>before</em> the connection is opened. A client with the wrong mods is
 * usually refused during the handshake, so anything that needed the connection to start
 * would never get the chance to fix the mods that got it refused.
 *
 * <p>Client-only: this class must never be referenced from code a dedicated server loads.
 */
public final class ModSyncClient {

    private static final Logger LOG = LoggerFactory.getLogger("ModSync");

    private static final AtomicBoolean APPLIER_ARMED = new AtomicBoolean();

    private static ModSyncPaths paths;
    private static String loader;
    private static String mcVersion;
    private static Supplier<List<Path>> modClasspath;

    /** Set while ModSync itself re-issues a connect, so the mixin lets that one through. */
    private static boolean bypassNextConnect;

    private ModSyncClient() {}

    /**
     * @param loader this client's loader id as manifests spell it, e.g. {@code neoforge}
     * @param mcVersion the running Minecraft version, from the loader rather than a build-time
     *     constant
     * @param modClasspath ModSync's own jar (or its dev output directories), for the applier
     */
    public static void init(Path gameDir, String loader, String mcVersion,
            Supplier<List<Path>> modClasspath) {
        ModSyncClient.paths = new ModSyncPaths(gameDir);
        ModSyncClient.loader = loader;
        ModSyncClient.mcVersion = mcVersion;
        ModSyncClient.modClasspath = modClasspath;

        try {
            ModSyncConfig.loadOrCreate(paths.config());
            ServerManifests.loadOrCreate(paths);
        } catch (IOException e) {
            LOG.error("ModSync config is unusable, syncing is disabled until it is fixed: {}",
                e.getMessage());
        }
        // A journal left over from an earlier session means its swap never happened (the
        // helper timed out or failed). Try again when this session exits.
        if (Files.exists(paths.journal())) {
            LOG.warn("A sync from an earlier session was never applied; retrying on exit");
            armApplierOnExit();
        }
    }

    /**
     * Called from the {@code ConnectScreen.startConnecting} mixin before any connection is made.
     *
     * @param connect re-issues the original connect, with the original arguments
     * @return true to cancel the original connect, because ModSync has taken over
     */
    public static boolean interceptConnect(Screen parent, ServerAddress address,
            Runnable connect) {
        if (bypassNextConnect) {
            bypassNextConnect = false;
            return false;
        }
        if (paths == null) {
            return false;
        }

        ModSyncConfig config;
        String url;
        try {
            // Re-read on every join, so an edit takes effect without restarting the game.
            url = ServerManifests.load(paths.serverManifests())
                .urlFor(address.getHost(), address.getPort());
            if (url == null) {
                LOG.info("No manifest configured for {}:{} in {}, joining without syncing",
                    address.getHost(), address.getPort(), paths.serverManifests());
                return false;
            }
            config = ModSyncConfig.load(paths.config());
        } catch (IOException e) {
            LOG.error("Not syncing: {}", e.getMessage());
            return false;
        }

        LOG.info("Checking {} before joining {}:{}", url, address.getHost(), address.getPort());
        String fallbackProfile = ModSyncPaths.sanitize(address.getHost() + "_" + address.getPort());
        Minecraft.getInstance().setScreen(new SyncScreen(parent, new SyncScreen.Target(
            paths, config, url, fallbackProfile, loader, mcVersion), () -> {
                bypassNextConnect = true;
                connect.run();
            }));
        return true;
    }

    /**
     * Makes sure the applier is started when the game exits. Idempotent.
     *
     * <p>A shutdown hook rather than an explicit call, so the swap happens however the player
     * leaves: the quit button on the sync screen, the title screen, or closing the window.
     */
    static void armApplierOnExit() {
        if (!APPLIER_ARMED.compareAndSet(false, true)) {
            return;
        }
        ModSyncPaths p = paths;
        Supplier<List<Path>> classpath = modClasspath;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!Files.exists(p.journal())) {
                return;
            }
            try {
                ApplierLauncher.launch(classpath.get(), p);
            } catch (IOException | RuntimeException e) {
                // The logger may already be shut down this late; stderr still reaches the
                // launcher's log.
                System.err.println("[ModSync] could not start the applier; it will be retried "
                    + "when the game next exits: " + e);
            }
        }, "ModSync-Applier-Launch"));
    }
}

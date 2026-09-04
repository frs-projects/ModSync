package net.frsprojects.modsync.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.frsprojects.modsync.ModSync;
import net.frsprojects.modsync.core.config.ModSyncConfig;
import net.frsprojects.modsync.core.export.ExportProgress;
import net.frsprojects.modsync.core.export.ExportRequest;
import net.frsprojects.modsync.core.export.ExportScanner;
import net.frsprojects.modsync.core.export.ExportService;
import net.frsprojects.modsync.core.export.JsonHttp;
import net.frsprojects.modsync.core.export.ModMetadataLookup;
import net.frsprojects.modsync.core.profile.ModSyncPaths;
import net.minecraft.commands.SharedSuggestionProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The {@code /modsync} command tree, defined exactly once.
 *
 * <p>Brigadier is generic over its source type, and the four registration paths hand us three
 * incompatible ones — Fabric's {@code FabricClientCommandSource} and, on the other loaders and
 * on servers, {@code CommandSourceStack}. Rather than maintain four near-identical trees, this
 * builds one tree parameterised on {@code S} and takes the two things that actually differ as
 * arguments: how to decide who may run it, and how to talk back to them.
 *
 * <p>This class must reference only Brigadier and loader-neutral Minecraft classes.
 * {@code Minecraft} is client-only and would abort a dedicated server the moment this class
 * loaded, so the thread hop and the chat call both live behind {@link Channel}.
 */
public final class ModSyncCommand {

    /** Where a command's output goes, and which thread it has to go there on. */
    public interface Channel {

        /** The game's main thread. Feedback must not touch a source from the worker. */
        Executor mainThread();

        void info(String text);

        void warn(String text);

        void error(String text);
    }

    // One at a time, named, and daemon: an export must never hold up JVM shutdown, and a
    // thread called "Thread-7" in a crash report tells nobody anything.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ModSync-Export");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private ModSyncCommand() {}

    public static <S> LiteralArgumentBuilder<S> build(
            Predicate<S> requirement,
            Function<S, Channel> channelFor,
            Supplier<Path> gameDir,
            boolean dedicatedServer) {

        return LiteralArgumentBuilder.<S>literal("modsync")
            .requires(requirement)
            .then(LiteralArgumentBuilder.<S>literal("export")
                .then(folderChain(channelFor, gameDir, dedicatedServer, false))
                // 'resolve' sits before the folder so the optional packName/packVersion chain
                // stays intact; Brigadier matches literals before arguments, and no allowlisted
                // folder is called "resolve".
                .then(LiteralArgumentBuilder.<S>literal("resolve")
                    .then(folderChain(channelFor, gameDir, dedicatedServer, true))));
    }

    private static <S> RequiredArgumentBuilder<S, String> folderChain(
            Function<S, Channel> channelFor, Supplier<Path> gameDir,
            boolean dedicatedServer, boolean resolveUrls) {

        return RequiredArgumentBuilder.<S, String>argument("folder", StringArgumentType.word())
            .suggests((ctx, builder) ->
                SharedSuggestionProvider.suggest(ExportScanner.allowedRoots(), builder))
            .executes(ctx -> run(ctx, channelFor, gameDir, dedicatedServer, resolveUrls,
                null, null))
            .then(RequiredArgumentBuilder.<S, String>argument("packName",
                    StringArgumentType.string())
                .executes(ctx -> run(ctx, channelFor, gameDir, dedicatedServer, resolveUrls,
                    StringArgumentType.getString(ctx, "packName"), null))
                .then(RequiredArgumentBuilder.<S, String>argument("packVersion",
                        StringArgumentType.string())
                    .executes(ctx -> run(ctx, channelFor, gameDir, dedicatedServer, resolveUrls,
                        StringArgumentType.getString(ctx, "packName"),
                        StringArgumentType.getString(ctx, "packVersion")))));
    }

    private static <S> int run(CommandContext<S> ctx, Function<S, Channel> channelFor,
            Supplier<Path> gameDir, boolean dedicatedServer, boolean resolveUrls,
            String packName, String packVersion) {

        String folder = StringArgumentType.getString(ctx, "folder");
        Channel channel = channelFor.apply(ctx.getSource());

        if (!ExportScanner.isAllowedRoot(folder)) {
            channel.error("'" + folder + "' is not an exportable folder. Expected one of "
                + String.join(", ", ExportScanner.allowedRoots()) + ".");
            return 0;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            channel.error("An export is already running.");
            return 0;
        }
        if (dedicatedServer) {
            channel.warn("Exporting on a dedicated server: client-only content such as "
                + "shaderpacks and client mods is not installed here and will be missing.");
        }
        if (!resolveUrls) {
            channel.info("Exporting " + folder + "/ without download URLs. "
                + "Use '/modsync export resolve " + folder + "' to look them up.");
        }

        Path dir = gameDir.get();
        WORKER.execute(() -> {
            try {
                export(dir, folder, packName, packVersion, resolveUrls, channel);
            } catch (Throwable t) {
                // A worker thread that dies silently leaves the player staring at nothing.
                post(channel, () -> channel.error("Export failed: " + t));
            } finally {
                RUNNING.set(false);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static void export(Path gameDir, String folder, String packName, String packVersion,
            boolean resolveUrls, Channel channel) throws Exception {

        ExportProgress progress = new ExportProgress() {
            @Override
            public void message(String text) {
                post(channel, () -> channel.info(text));
            }

            @Override
            public void finished(Path output, int total, int resolved, int unresolved) {
                post(channel, () -> {
                    channel.info("Exported " + total + " file" + (total == 1 ? "" : "s")
                        + " to " + output.getFileName() + " (in modsync/exports).");
                    if (unresolved > 0) {
                        channel.warn(unresolved + " file" + (unresolved == 1 ? " has" : "s have")
                            + " no download URL and must be filled in before publishing.");
                    }
                });
            }

            @Override
            public void failed(String reason) {
                post(channel, () -> channel.error(reason));
            }
        };

        ModSyncPaths paths = new ModSyncPaths(gameDir);
        ModSyncConfig config = ModSyncConfig.load(paths.config());

        if (!resolveUrls) {
            runExport(gameDir, folder, packName, packVersion, List.of(), progress, channel);
            return;
        }
        try (JsonHttp http = new JsonHttp(ExportService.lookupAllowlist(config),
                "ModSync/" + ModSync.VERSION)) {
            List<ModMetadataLookup> lookups =
                ExportService.defaultLookups(gameDir, config, http, progress);
            runExport(gameDir, folder, packName, packVersion, lookups, progress, channel);
        }
    }

    private static void runExport(Path gameDir, String folder, String packName,
            String packVersion, List<ModMetadataLookup> lookups, ExportProgress progress,
            Channel channel) throws Exception {
        try {
            ExportService.export(
                new ExportRequest(gameDir, folder, packName, packVersion, lookups), progress);
        } catch (net.frsprojects.modsync.core.export.ExportException e) {
            // Expected, actionable failures: say what happened, not how it was thrown.
            post(channel, () -> channel.error(e.getMessage()));
        }
    }

    private static void post(Channel channel, Runnable action) {
        channel.mainThread().execute(action);
    }
}

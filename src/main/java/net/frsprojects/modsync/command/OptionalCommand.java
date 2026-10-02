package net.frsprojects.modsync.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.frsprojects.modsync.core.profile.ModSyncPaths;
import net.frsprojects.modsync.core.sync.OptionalChoices;
import net.minecraft.commands.SharedSuggestionProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * {@code /modsync optional}: review and change what the player picked from a pack's
 * recommended and optional files.
 *
 * <ul>
 *   <li>{@code optional <pack>} lists the remembered choices.
 *   <li>{@code optional <pack> remove <mod>} declines a file the player took; the next sync
 *       moves it aside.
 *   <li>{@code optional <pack> forget <mod>} and {@code optional <pack> reset} undo
 *       "don't ask again", so missing files are offered on the next join.
 * </ul>
 *
 * <p>A stopgap: removing a mod should be a screen, not a command. Client-only, since the
 * choices live in the player's game directory. Like {@link ModSyncCommand}, it references only
 * Brigadier and loader-neutral Minecraft classes.
 */
final class OptionalCommand {

    private OptionalCommand() {}

    static <S> LiteralArgumentBuilder<S> build(Function<S, ModSyncCommand.Channel> channelFor,
            Supplier<Path> gameDir) {
        return LiteralArgumentBuilder.<S>literal("optional")
            .then(RequiredArgumentBuilder.<S, String>argument("pack", StringArgumentType.word())
                .suggests((ctx, builder) ->
                    SharedSuggestionProvider.suggest(packsWithChoices(gameDir.get()), builder))
                .executes(ctx -> list(ctx, channelFor, gameDir))
                .then(LiteralArgumentBuilder.<S>literal("remove")
                    .then(OptionalCommand.<S>modArgument(gameDir, true)
                        .executes(ctx -> remove(ctx, channelFor, gameDir))))
                .then(LiteralArgumentBuilder.<S>literal("forget")
                    .then(OptionalCommand.<S>modArgument(gameDir, false)
                        .executes(ctx -> forget(ctx, channelFor, gameDir))))
                .then(LiteralArgumentBuilder.<S>literal("reset")
                    .executes(ctx -> reset(ctx, channelFor, gameDir))));
    }

    // Greedy, because identities such as "modrinth:sodium" or "mods/x.jar" are not words.
    private static <S> RequiredArgumentBuilder<S, String> modArgument(Supplier<Path> gameDir,
            boolean installedOnly) {
        return RequiredArgumentBuilder.<S, String>argument("mod", StringArgumentType.greedyString())
            .suggests((ctx, builder) -> {
                OptionalChoices choices = load(gameDir.get(),
                    StringArgumentType.getString(ctx, "pack"));
                Stream<String> ids = choices.all().entrySet().stream()
                    .filter(e -> !installedOnly || e.getValue().install())
                    .map(e -> e.getKey());
                return SharedSuggestionProvider.suggest(ids, builder);
            });
    }

    private static <S> int list(CommandContext<S> ctx,
            Function<S, ModSyncCommand.Channel> channelFor, Supplier<Path> gameDir) {
        ModSyncCommand.Channel channel = channelFor.apply(ctx.getSource());
        String pack = StringArgumentType.getString(ctx, "pack");
        OptionalChoices choices = load(gameDir.get(), pack);
        if (choices.all().isEmpty()) {
            channel.info("No optional file choices remembered for " + pack + ".");
            return Command.SINGLE_SUCCESS;
        }
        channel.info("Optional file choices for " + pack + ":");
        choices.all().forEach((id, c) -> {
            String line = (c.install() ? "✔ " : "✕ ") + c.label() + " [" + id + "] — "
                + (c.install() ? "installed" : "declined")
                + (c.quiet() ? ", not asked again" : "");
            if (c.install()) {
                channel.info(line);
            } else {
                channel.warn(line);
            }
        });
        channel.info("'/modsync optional " + pack + " remove <mod>' removes one on the next "
            + "join; 'forget <mod>' or 'reset' asks again.");
        return Command.SINGLE_SUCCESS;
    }

    private static <S> int remove(CommandContext<S> ctx,
            Function<S, ModSyncCommand.Channel> channelFor, Supplier<Path> gameDir) {
        ModSyncCommand.Channel channel = channelFor.apply(ctx.getSource());
        String pack = StringArgumentType.getString(ctx, "pack");
        String mod = StringArgumentType.getString(ctx, "mod").trim();
        OptionalChoices choices = load(gameDir.get(), pack);
        OptionalChoices.Choice before = choices.all().get(mod);
        if (before == null) {
            channel.error("No choice remembered for '" + mod + "' in " + pack + ".");
            return 0;
        }
        if (!before.install()) {
            channel.info(before.label() + " is already declined.");
            return Command.SINGLE_SUCCESS;
        }
        choices.decline(mod);
        if (!save(gameDir.get(), pack, choices, channel)) {
            return 0;
        }
        channel.info(before.label() + " will be moved aside the next time you join this pack's "
            + "server, and then needs a restart like any sync. It goes to modsync/quarantine, "
            + "not the bin.");
        return Command.SINGLE_SUCCESS;
    }

    private static <S> int forget(CommandContext<S> ctx,
            Function<S, ModSyncCommand.Channel> channelFor, Supplier<Path> gameDir) {
        ModSyncCommand.Channel channel = channelFor.apply(ctx.getSource());
        String pack = StringArgumentType.getString(ctx, "pack");
        String mod = StringArgumentType.getString(ctx, "mod").trim();
        OptionalChoices choices = load(gameDir.get(), pack);
        OptionalChoices.Choice before = choices.all().get(mod);
        if (before == null) {
            channel.error("No choice remembered for '" + mod + "' in " + pack + ".");
            return 0;
        }
        choices.forget(mod);
        if (!save(gameDir.get(), pack, choices, channel)) {
            return 0;
        }
        channel.info(before.label() + " will be offered again the next time it is missing "
            + "or changes.");
        return Command.SINGLE_SUCCESS;
    }

    private static <S> int reset(CommandContext<S> ctx,
            Function<S, ModSyncCommand.Channel> channelFor, Supplier<Path> gameDir) {
        ModSyncCommand.Channel channel = channelFor.apply(ctx.getSource());
        String pack = StringArgumentType.getString(ctx, "pack");
        OptionalChoices choices = load(gameDir.get(), pack);
        choices.clear();
        if (!save(gameDir.get(), pack, choices, channel)) {
            return 0;
        }
        channel.info("Every missing recommended and optional file of " + pack
            + " will be offered again on the next join.");
        return Command.SINGLE_SUCCESS;
    }

    private static OptionalChoices load(Path gameDir, String pack) {
        return OptionalChoices.load(new ModSyncPaths(gameDir).optionalChoices(pack));
    }

    private static boolean save(Path gameDir, String pack, OptionalChoices choices,
            ModSyncCommand.Channel channel) {
        try {
            choices.save(new ModSyncPaths(gameDir).optionalChoices(pack));
            return true;
        } catch (IOException e) {
            channel.error("Could not save the choices: " + e.getMessage());
            return false;
        }
    }

    private static List<String> packsWithChoices(Path gameDir) {
        ModSyncPaths paths = new ModSyncPaths(gameDir);
        if (!Files.isDirectory(paths.profilesRoot())) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(paths.profilesRoot())) {
            return dirs.map(d -> d.getFileName().toString())
                .filter(id -> Files.isRegularFile(paths.optionalChoices(id)))
                .sorted()
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}

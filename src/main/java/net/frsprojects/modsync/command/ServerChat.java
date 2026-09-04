package net.frsprojects.modsync.command;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executor;

/**
 * Output for the dedicated-server command, sent back through the command source the way
 * vanilla commands do.
 *
 * <p>{@code broadcastToOps} is false throughout: an export prints a line per step, and every
 * other operator on the server does not need that in their chat.
 */
public final class ServerChat implements ModSyncCommand.Channel {

    private final CommandSourceStack source;

    private ServerChat(CommandSourceStack source) {
        this.source = source;
    }

    public static ModSyncCommand.Channel of(CommandSourceStack source) {
        return new ServerChat(source);
    }

    @Override
    public Executor mainThread() {
        return source.getServer();
    }

    @Override
    public void info(String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    @Override
    public void warn(String text) {
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.YELLOW), false);
    }

    @Override
    public void error(String text) {
        source.sendFailure(Component.literal(text).withStyle(ChatFormatting.RED));
    }
}

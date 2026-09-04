package net.frsprojects.modsync.command;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executor;

/**
 * Client-side output for {@code /modsync}, written straight to the chat overlay.
 *
 * <p>Deliberately not routed through the command source. On Forge 1.20.1 a client command's
 * source is the {@code LocalPlayer} itself, whose {@code acceptsSuccess()} reads the
 * {@code sendCommandFeedback} gamerule — so an export's progress would be silently swallowed
 * on any server that turns that rule off. Writing to the chat component directly also means
 * nothing captures a source across threads, so a disconnect mid-export cannot leave the
 * worker holding a dead {@code ClientPacketListener}.
 *
 * <p>Client-only: this class must never be referenced from code a dedicated server loads.
 */
public final class ClientChat implements ModSyncCommand.Channel {

    public static final ClientChat INSTANCE = new ClientChat();

    private ClientChat() {}

    @Override
    public Executor mainThread() {
        return Minecraft.getInstance();
    }

    @Override
    public void info(String text) {
        say(Component.literal(text));
    }

    @Override
    public void warn(String text) {
        say(Component.literal(text).withStyle(ChatFormatting.YELLOW));
    }

    @Override
    public void error(String text) {
        say(Component.literal(text).withStyle(ChatFormatting.RED));
    }

    private static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) {
            mc.gui.getChat().addMessage(message);
        }
    }
}

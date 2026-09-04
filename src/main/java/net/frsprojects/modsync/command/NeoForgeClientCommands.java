//? if neoforge {
package net.frsprojects.modsync.command;

import net.minecraft.commands.CommandSourceStack;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

// The client half of NeoForgeCommands. Loaded only on a physical client.
public final class NeoForgeClientCommands {

    private NeoForgeClientCommands() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class,
            NeoForgeClientCommands::onRegister);
    }

    private static void onRegister(RegisterClientCommandsEvent event) {
        // A client command never reaches a server, so there is nobody to ask for permission.
        event.getDispatcher().register(ModSyncCommand.<CommandSourceStack>build(
            source -> true,
            source -> ClientChat.INSTANCE,
            () -> FMLPaths.GAMEDIR.get(),
            false));
    }
}
//?}

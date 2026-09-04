//? if forge {
/*package net.frsprojects.modsync.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.loading.FMLPaths;

// The client half of ForgeCommands. Loaded only on a physical client.
public final class ForgeClientCommands {

    private ForgeClientCommands() {}

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(ForgeClientCommands::onRegister);
    }

    private static void onRegister(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(ModSyncCommand.<CommandSourceStack>build(
            source -> true,
            source -> ClientChat.INSTANCE,
            () -> FMLPaths.GAMEDIR.get(),
            false));
    }
}
*///?}

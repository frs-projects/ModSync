//? if forge {
/*package net.frsprojects.modsync.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

// Registers /modsync on Forge.
//
// Same shape as the NeoForge half, with two differences worth knowing: Forge's eventbus 6 has
// no addListener(Class, Consumer) overload and cannot infer an event type from an untyped
// lambda, so listeners are method references; and the client half is isolated behind a dist
// check because naming a client type here would abort a dedicated server.
public final class ForgeCommands {

    private ForgeCommands() {}

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(ForgeCommands::onRegister);
        if (FMLEnvironment.dist.isClient()) {
            ForgeClientCommands.init();
        }
    }

    private static void onRegister(RegisterCommandsEvent event) {
        // Only a dedicated server gets the server-side variant, so it can never collide with
        // the client command in singleplayer.
        if (event.getCommandSelection() != Commands.CommandSelection.DEDICATED) {
            return;
        }
        event.getDispatcher().register(ModSyncCommand.<CommandSourceStack>build(
            source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS),
            ServerChat::of,
            () -> FMLPaths.GAMEDIR.get(),
            true));
    }
}
*///?}

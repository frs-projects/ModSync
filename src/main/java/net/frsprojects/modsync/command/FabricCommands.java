//? if fabric {
/*package net.frsprojects.modsync.command;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

// Registers /modsync on Fabric.
//
// The two callbacks are separate events with different source types, which is why the command
// tree is generic: the client path speaks FabricClientCommandSource and the server path
// CommandSourceStack.
public final class FabricCommands {

    private FabricCommands() {}

    public static void initServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // Only a dedicated server gets the server-side variant, so it can never collide
            // with the client command in singleplayer.
            if (environment != Commands.CommandSelection.DEDICATED) {
                return;
            }
            dispatcher.register(ModSyncCommand.<CommandSourceStack>build(
                source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS),
                ServerChat::of,
                () -> FabricLoader.getInstance().getGameDir(),
                true));
        });
    }

    public static void initClient() {
        // A client command never reaches a server, so there is nobody to ask for permission.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ModSyncCommand.<FabricClientCommandSource>build(
                source -> true,
                source -> ClientChat.INSTANCE,
                () -> FabricLoader.getInstance().getGameDir(),
                false)));
    }
}
*///?}

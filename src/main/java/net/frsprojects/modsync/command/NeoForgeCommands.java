//? if neoforge {
package net.frsprojects.modsync.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

// Registers /modsync on NeoForge.
//
// Both command events are game-bus events, so they go on NeoForge.EVENT_BUS rather than the
// mod bus, and the existing no-arg @Mod constructor is enough to subscribe.
//
// The client half lives in a separate class reached only behind a dist check: naming a client
// type anywhere in this class would pull it into the constant pool and abort a dedicated
// server, where those classes do not exist.
//
// Only line comments here, and in every other loader-gated file: Stonecutter comments an
// inactive file out with /* */, and a */ inside a block comment would close it early.
public final class NeoForgeCommands {

    private NeoForgeCommands() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, NeoForgeCommands::onRegister);
        if (FMLEnvironment.dist.isClient()) {
            NeoForgeClientCommands.init();
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
//?}

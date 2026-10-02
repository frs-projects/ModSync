package net.frsprojects.modsync.mixin;

import net.frsprojects.modsync.client.ModSyncClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.20.5 {
import net.minecraft.client.multiplayer.TransferState;
//?}

// Every way of joining a server (the server list, direct connect, quick play, a transfer)
// funnels through ConnectScreen.startConnecting before a socket is opened, which makes it the
// one place to sync first. Neither loader has an event that fires this early.
//
// Only line comments here: Stonecutter comments out the inactive signature with /* */.
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {

    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void modsync$syncBeforeConnecting(Screen parent, Minecraft minecraft,
            ServerAddress address, ServerData data, boolean quickPlay,
            //? if >=1.20.5 {
            TransferState transferState,
            //?}
            CallbackInfo ci) {
        Runnable connect = () -> ConnectScreen.startConnecting(parent, minecraft, address, data,
            //? if >=1.20.5 {
            quickPlay, transferState);
            //?} else {
            /*quickPlay);
            *///?}
        if (ModSyncClient.interceptConnect(parent, address, connect)) {
            ci.cancel();
        }
    }
}

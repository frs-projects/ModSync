package net.frsprojects.modsync.mixin;

import net.frsprojects.modsync.client.ModSyncClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The title screen is the first point at which the game is loaded and the player has not yet
// opened a world, which makes it the place to run a startup sync. Fabric has no event that can
// replace a screen before it opens, so this is a mixin on every loader.
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void modsync$syncAtStartup(Screen screen, CallbackInfo ci) {
        if (screen instanceof TitleScreen title && ModSyncClient.interceptTitleScreen(title)) {
            ci.cancel();
        }
    }
}

package world.anhgelus.floodhunt.client.mixin;

import net.minecraft.client.gui.hud.PlayerListHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.anhgelus.floodhunt.client.FloodhuntClient;

@Mixin(PlayerListHud.class)
public class NoPlayerListHud {
	@Inject(at = @At("HEAD"), method = "render", cancellable = true)
	public void render(CallbackInfo ci) {
		if (FloodhuntClient.showTab() || !FloodhuntClient.gameStarted()) return;
		ci.cancel();
	}
}

package world.anhgelus.floodhunt.client.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.anhgelus.floodhunt.Floodhunt;
import world.anhgelus.floodhunt.client.FloodhuntClient;

@Mixin(AbstractClientPlayerEntity.class)
public class NoSkin {
	@Inject(at = @At("HEAD"), method = "getSkin", cancellable = true)
	public void getSkin(CallbackInfoReturnable<SkinTextures> cir) {
		if (FloodhuntClient.showSkins() || !FloodhuntClient.gameStarted()) return;
		cir.setReturnValue(SkinTextures.create(
			new AssetInfo.TextureAssetInfo(Identifier.of(Floodhunt.MOD_ID, "skin")),
			null,
			null,
			PlayerSkinType.WIDE
		));
	}
}

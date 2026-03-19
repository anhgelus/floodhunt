package world.anhgelus.floodhunt.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.dimension.PortalManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.anhgelus.floodhunt.Floodhunt;

@Mixin(PortalManager.class)
public class NoPortals {
	@Inject(at = @At("HEAD"), method = "tick", cancellable = true)
	public void disableTick(ServerWorld world, Entity entity, boolean canUsePortals, CallbackInfoReturnable<Boolean> cir) {
		if (Floodhunt.CONFIG == null || Floodhunt.CONFIG.portalsEnabled()) return;
		cir.setReturnValue(false);
	}
}

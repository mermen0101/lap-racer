package dev.laprace.mixin;

import dev.laprace.LapConfig;
import dev.laprace.car.CarState;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player sitting in a boat drawn as the F1 car: the car draws them (as its driver), the player model is hidden. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"), require = 1)
	private void laprace$inCar(Avatar avatar, AvatarRenderState state, float partial, CallbackInfo ci) {
		((CarState.Driver) state).laprace$setInCar(LapConfig.INSTANCE.carModel && avatar.getVehicle() instanceof AbstractBoat);
	}
}

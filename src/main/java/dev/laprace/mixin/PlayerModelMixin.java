package dev.laprace.mixin;

import dev.laprace.car.CarState;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The player model of a driver in the F1 car is not drawn (the car draws the driver). */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"), require = 1)
	private void laprace$hideInCar(AvatarRenderState state, CallbackInfo ci) {
		((PlayerModel) (Object) this).root().visible = !((CarState.Driver) state).laprace$inCar();
	}
}

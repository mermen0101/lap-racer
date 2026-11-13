package dev.laprace.mixin;

import dev.laprace.car.CarRenderer;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In the F1 car the camera sits lower (the driver sits deep in the cockpit), as the avatar's camera height. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private Entity entity;

	@Shadow
	public abstract Vec3 position();

	@Shadow
	protected abstract void setPosition(Vec3 pos);

	@Inject(method = "alignWithEntity", at = @At("TAIL"), require = 1)
	private void laprace$carCamera(float partial, CallbackInfo ci) {
		double drop = CarRenderer.cameraDrop(entity);
		if (drop != 0) {
			setPosition(position().add(0, drop, 0));
		}
	}
}

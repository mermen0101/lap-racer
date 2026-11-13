package dev.laprace.mixin;

import dev.laprace.LapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In the F1 car the driver's arms are on the steering wheel (the car draws them): no bare first-person arm. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsMixin {
	@Inject(method = "renderPlayerArm", at = @At("HEAD"), cancellable = true, require = 1)
	private void laprace$noArmInCar(CallbackInfo ci) {
		var p = Minecraft.getInstance().player;
		if (LapConfig.INSTANCE.carModel && p != null && p.getVehicle() instanceof AbstractBoat) {
			ci.cancel();
		}
	}
}

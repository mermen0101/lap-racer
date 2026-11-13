package dev.laprace.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.laprace.car.CarRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.AbstractBoatRenderer;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every boat (and raft) is drawn as the F1 car. */
@Mixin(AbstractBoatRenderer.class)
public abstract class AbstractBoatRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;Lnet/minecraft/client/renderer/entity/state/BoatRenderState;F)V",
			at = @At("TAIL"), require = 1)
	private void laprace$extractCar(AbstractBoat boat, BoatRenderState state, float partial, CallbackInfo ci) {
		try {
			CarRenderer.extract(boat, state, partial);
		} catch (RuntimeException e) {
			dev.laprace.LapRaceClient.LOG.error("F1 car state", e);
		}
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/BoatRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("HEAD"), cancellable = true, require = 1)
	private void laprace$submitCar(BoatRenderState state, PoseStack ps, SubmitNodeCollector out, CameraRenderState camera, CallbackInfo ci) {
		if (CarRenderer.submit(state, ps, out)) {
			ci.cancel();
		}
	}
}

package dev.laprace.mixin;

import dev.laprace.cruise.Analog;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Analog keys for the 80 km/h autopilot: while it drives, vanilla's {@code controlBoat()} runs line for line with
 * the keys pressed partway (steering -1..+1 into deltaRotation, throttle -0.005..+0.04 along the bow). Friction,
 * collisions and turning momentum stay vanilla. Not required: without it the autopilot pulses the real keys.
 */
@Mixin(value = AbstractBoat.class, priority = 500)
public abstract class AbstractBoatMixin {
	@Shadow
	private float deltaRotation;
	@Shadow
	private boolean inputLeft;
	@Shadow
	private boolean inputRight;

	/** An empty tank, on the keys: the car turns, nothing pushes it (vanilla gives a turn a little push too). */
	@Inject(method = "controlBoat", at = @At("HEAD"), cancellable = true)
	private void laprace$dry(CallbackInfo ci) {
		AbstractBoat self = (AbstractBoat) (Object) this;
		if (!dev.laprace.Engine.empty() || !self.isVehicle() || (Analog.active && Analog.boat == self)) {
			return;
		}
		if (this.inputLeft) {
			this.deltaRotation -= 1.0f;
		}
		if (this.inputRight) {
			this.deltaRotation += 1.0f;
		}
		self.setYRot(self.getYRot() + this.deltaRotation);
		self.setPaddleState(false, false);
		ci.cancel();
	}

	/** The ghost (one's best lap, a client-only boat): nothing touches it, it touches nothing. */
	@Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
	private void laprace$ghostCollide(net.minecraft.world.entity.Entity other, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (dev.laprace.Ghost.isGhost((AbstractBoat) (Object) this) || dev.laprace.Ghost.isGhost(other)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "canBeCollidedWith", at = @At("HEAD"), cancellable = true)
	private void laprace$ghostSolid(net.minecraft.world.entity.Entity other, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (dev.laprace.Ghost.isGhost((AbstractBoat) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = {"isPushable", "isPickable"}, at = @At("HEAD"), cancellable = true)
	private void laprace$ghostPush(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (dev.laprace.Ghost.isGhost((AbstractBoat) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "controlBoat", at = @At("HEAD"), cancellable = true)
	private void laprace$analog(CallbackInfo ci) {
		AbstractBoat self = (AbstractBoat) (Object) this;
		if (!Analog.active || Analog.boat != self || !self.isVehicle()) {
			return;
		}
		Analog.hooked = true;
		float steer = Math.clamp(Analog.steer, -1f, 1f);
		float f = Math.clamp(Analog.accel, -0.005f, 0.04f);
		boolean dry = dev.laprace.Engine.empty();
		if (dry) {
			f = 0f; // an empty tank: steering alone
		}
		this.deltaRotation += steer;
		if (f == 0f && !dry) {
			f = 0.005f * Math.abs(steer);
		}
		self.setYRot(self.getYRot() + this.deltaRotation);
		double yaw = Math.toRadians(self.getYRot());
		self.setDeltaMovement(self.getDeltaMovement().add(-Math.sin(yaw) * f, 0.0, Math.cos(yaw) * f));
		boolean up = Analog.accel > 1.0E-4f;
		self.setPaddleState(!dry && (steer > 0.05f || up), !dry && (steer < -0.05f || up));
		ci.cancel();
	}
}

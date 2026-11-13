package dev.laprace.mixin;

import dev.laprace.car.CarState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements CarState.Driver {
	@Unique
	private boolean laprace$inCar;

	@Override
	public boolean laprace$inCar() {
		return laprace$inCar;
	}

	@Override
	public void laprace$setInCar(boolean v) {
		laprace$inCar = v;
	}
}

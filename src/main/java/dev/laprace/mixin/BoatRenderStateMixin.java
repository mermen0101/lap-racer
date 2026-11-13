package dev.laprace.mixin;

import dev.laprace.car.CarState;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Each boat's render state carries what the F1 car needs. */
@Mixin(BoatRenderState.class)
public abstract class BoatRenderStateMixin implements CarState.Holder {
	@Unique
	private final CarState laprace$car = new CarState();

	@Override
	public CarState laprace$car() {
		return laprace$car;
	}
}

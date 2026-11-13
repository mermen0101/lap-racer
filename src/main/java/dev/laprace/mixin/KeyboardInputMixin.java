package dev.laprace.mixin;

import dev.laprace.cruise.Cruise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right after vanilla reads the keyboard: the 80 km/h autopilot, if on, replaces the keys. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void laprace$cruise(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			if (dev.laprace.Perf.ON && Cruise.enabled) {
				long t0 = System.nanoTime();
				this.keyPresses = Cruise.apply(mc.player, this.keyPresses);
				dev.laprace.Perf.cruise(System.nanoTime() - t0);
			} else {
				this.keyPresses = Cruise.apply(mc.player, this.keyPresses);
			}
			// driving by hand in the pit lane: no faster than the pit limit
			this.keyPresses = dev.laprace.cruise.PitLimiter.apply(mc.player, this.keyPresses);
			// an empty tank: no throttle and no reverse, the steering alone
			if (dev.laprace.Engine.empty() && (this.keyPresses.forward() || this.keyPresses.backward())) {
				var k = this.keyPresses;
				this.keyPresses = new net.minecraft.world.entity.player.Input(false, false, k.left(), k.right(), k.jump(), k.shift(), k.sprint());
			}
		}
	}
}

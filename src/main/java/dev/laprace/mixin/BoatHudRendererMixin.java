package dev.laprace.mixin;

import dev.laprace.BoatData;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boat HUD (hibi.boathud, optional) draws its own panel from {@code HudRenderer.render}. When "restyle Boat HUD" is
 * on, that panel is skipped and our speedometer draws Boat HUD's numbers instead. {@code @Pseudo}: without Boat HUD
 * the target class does not exist and this mixin is simply not applied.
 */
@Pseudo
@Mixin(targets = "hibi.boathud.HudRenderer", remap = false)
public abstract class BoatHudRendererMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
	private void laprace$replacePanel(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
		if (BoatData.replaceBoatHud()) {
			BoatData.boatHudHooked = true;
			ci.cancel();
		}
	}
}

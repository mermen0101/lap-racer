package dev.laprace.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Development only (LAPRACE_DEMO set): logs who asks for a clip rectangle reaching past the screen - the game
 * crashes on one ("Scissor ... out of bounds") only later, when it draws, with no trace of where it came from.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class ScissorProbeMixin {
	private static final boolean DEV = System.getenv("LAPRACE_DEMO") != null;
	private static int logged;

	@Inject(method = "enableScissor", at = @At("HEAD"), require = 0)
	private void laprace$probe(int x0, int y0, int x1, int y1, CallbackInfo ci) {
		if (!DEV || logged > 5) {
			return;
		}
		GuiGraphicsExtractor g = (GuiGraphicsExtractor) (Object) this;
		org.joml.Vector2f a = g.pose().transformPosition(x0, y0, new org.joml.Vector2f());
		org.joml.Vector2f b = g.pose().transformPosition(x1, y1, new org.joml.Vector2f());
		if (Math.min(a.x, b.x) < 0 || Math.min(a.y, b.y) < 0 || Math.max(a.x, b.x) > g.guiWidth() || Math.max(a.y, b.y) > g.guiHeight()) {
			logged++;
			dev.laprace.LapRaceClient.LOG.warn("[scissor] out of the screen {}x{}: {} {} - {} {}", g.guiWidth(), g.guiHeight(), a.x, a.y, b.x, b.y,
					new Throwable("who"));
		}
	}
}

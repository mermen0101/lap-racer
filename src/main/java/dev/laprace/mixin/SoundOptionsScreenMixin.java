package dev.laprace.mixin;

import dev.laprace.LapConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

/**
 * The game's own Music & Sounds screen gets one more slider among the sound categories: the boat's engine
 * (LapConfig.engineVolume, the same value as in the mod's settings).
 */
@Mixin(SoundOptionsScreen.class)
public abstract class SoundOptionsScreenMixin {
	@Inject(method = "getAllSoundOptionsExceptMaster", at = @At("RETURN"), cancellable = true)
	private void laprace$engineSlider(CallbackInfoReturnable<OptionInstance<?>[]> cir) {
		OptionInstance<?>[] vanilla = cir.getReturnValue();
		OptionInstance<?>[] all = Arrays.copyOf(vanilla, vanilla.length + 1);
		all[vanilla.length] = new OptionInstance<>("laprace.options.engine_volume", OptionInstance.noTooltip(),
				(caption, v) -> v == 0 ? Options.genericValueLabel(caption, Component.translatable("options.off"))
						: Component.translatable("options.percent_value", caption, (int) Math.round(v * 100)),
				OptionInstance.UnitDouble.INSTANCE, LapConfig.INSTANCE.engineVolume / 100.0, v -> {
					LapConfig.INSTANCE.engineVolume = (int) Math.round(v * 100);
					LapConfig.save();
				});
		cir.setReturnValue(all);
	}
}
